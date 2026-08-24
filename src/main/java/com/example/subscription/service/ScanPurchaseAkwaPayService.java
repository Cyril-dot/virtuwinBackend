package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.AkwaPayPendingScanIntent;
import com.example.subscription.model.ScanPlan;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanPurchaseStatus;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryAkwaPayPendingScanIntentRepository;
import com.example.subscription.repository.InMemoryScanPurchaseRepository;
import com.example.subscription.util.CodeGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AkwaPay integration for buying an AI scan purchase, as an alternative to
 * the manual-screenshot flow in {@link ScanPurchaseService}.
 *
 * A successful charge flips the matching {@link ScanPurchase} from
 * AWAITING_PAYMENT straight to APPROVED (no admin review) and records
 * referral commission. Pending intents are tracked in memory; see
 * {@link AkwaPayPendingScanIntent} for the restart-loss trade-off that
 * implies.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * GATEWAY CHANGE (2026-08-23) — READ THIS IF YOU ARE DEBUGGING A REGRESSION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay switched their default gateway from Moolre to Flutterwave v4. This
 * changes NOTHING about the API contract this service talks to — same
 * /v1/payment_intents endpoint, same auth, same webhook shape. Two concrete
 * behavioural differences matter here:
 *
 *   1. THE OTP FLOW HAS BEEN REMOVED FROM THIS SERVICE (and the controller).
 *      Flutterwave v4 mobile money uses next_action.type = "payment_instruction"
 *      (a push prompt), not "submit_otp". There is nothing for an OTP
 *      endpoint to relay any more.
 *
 *   2. next_action.type MAY VARY MORE THAN BEFORE.
 *      Callers already branch on next_action.type from the init response.
 *      Don't add logic that inspects hint or ussd_fallback string content;
 *      treat those as opaque.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * REFERENCE FORMAT — HYPHENS NOT UNDERSCORES
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Flutterwave uses our reference as tx_ref. Flutterwave's tx_ref validation
 * rejects underscores — only alphanumeric characters and hyphens are
 * accepted. References now use hyphens as separators:
 *
 *     sbscn-<32-hex nonce>-<8-hex nonce>     scan purchase
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHY customer.email IS SYNTHETIC (PER-ATTEMPT)
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Flutterwave v4 deduplicates customers by email. Reusing the real user
 * email causes "Customer already exists" on any second attempt while a
 * prior intent is still unresolved. We use plus-addressing to make the
 * email unique per attempt: kojo@gmail.com → kojo+1724449830123@gmail.com.
 * The real email is preserved in metadata for audit.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * RELIABILITY GUARANTEE
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Webhooks have never fired reliably in production. Treat the reconciliation
 * sweep ({@link #reconcilePendingScanIntents}) as the primary settlement
 * mechanism. Both paths dedupe via {@link #settlePurchase} checking that the
 * purchase is still AWAITING_PAYMENT, so no double-approval is possible
 * whichever wins the race.
 */
@Service
@EnableScheduling
public class ScanPurchaseAkwaPayService {

    private static final Logger log = LoggerFactory.getLogger(ScanPurchaseAkwaPayService.class);

    // Hyphens only — Flutterwave rejects underscores in tx_ref.
    private static final String REF_PREFIX_SCAN = "sbscn-";

    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration SWEEP_HEAD_START     = Duration.ofSeconds(5);

    private static final Duration TIER_HOT_UNTIL  = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL = Duration.ofMinutes(60);

    private static final Duration POLL_EVERY_HOT  = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD = Duration.ofMinutes(10);

    private static final Duration ABANDON_AFTER = Duration.ofHours(24);

    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final InMemoryScanPurchaseRepository            scanPurchaseRepository;
    private final InMemoryAkwaPayPendingScanIntentRepository pendingIntents;
    private final AccountService                             accountService;
    private final CommissionService                          commissionService;
    private final WebClient.Builder                          webClientBuilder;
    private final ObjectMapper                                objectMapper;

    @Value("${app.akwapay.secret-key}")     private String secretKey;
    @Value("${app.akwapay.webhook-secret}") private String webhookSecret;
    @Value("${app.akwapay.base-url}")       private String baseUrl;
    @Value("${app.platform.frontend-url}")  private String frontendUrl;

    private final Duration akwapayTimeout    = Duration.ofSeconds(15);
    private final long     akwapayRetryLimit = 2;

    public ScanPurchaseAkwaPayService(InMemoryScanPurchaseRepository scanPurchaseRepository,
                                      InMemoryAkwaPayPendingScanIntentRepository pendingIntents,
                                      AccountService accountService,
                                      CommissionService commissionService,
                                      WebClient.Builder webClientBuilder,
                                      ObjectMapper objectMapper) {
        this.scanPurchaseRepository = scanPurchaseRepository;
        this.pendingIntents = pendingIntents;
        this.accountService = accountService;
        this.commissionService = commissionService;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    // ─── Init ───────────────────────────────────────────────────────────────

    public Map<String, Object> initPurchase(String email, String planCode, String phone, String requestedNetwork) {
        if (email == null || email.isBlank()) {
            throw new ApiException("email is required", HttpStatus.BAD_REQUEST);
        }

        boolean inFlight = scanPurchaseRepository.findByEmail(email).stream()
                .anyMatch(p -> p.getStatus() == ScanPurchaseStatus.PENDING
                        || p.getStatus() == ScanPurchaseStatus.APPROVED
                        || p.getStatus() == ScanPurchaseStatus.AWAITING_PAYMENT);
        if (inFlight) {
            throw new ApiException(
                    "You already have a scan purchase that's pending, awaiting payment, or approved and not yet " +
                            "used. Finish using it before starting another.",
                    HttpStatus.CONFLICT);
        }

        ScanPlan plan = ScanPlan.fromCode(planCode);
        var network = resolveNetwork(requestedNetwork, phone);

        String purchaseId = CodeGenerator.generateId();
        String reference = buildReference();
        int amountPesewas = plan.getAmountCedis() * 100;

        log.info("initPurchase: email='{}' plan='{}' amountPesewas={} ref='{}' purchaseId='{}'",
                email, plan.name(), amountPesewas, reference, purchaseId);

        var akwapayResponse = akwapayCreateIntent(
                amountPesewas, reference, email, phone, network,
                frontendUrl + "/scan?payment=success",
                Map.of("purchaseId", purchaseId, "purpose", "scan_purchase", "realEmail", email.trim()));

        var intentId = String.valueOf(akwapayResponse.get("id"));

        ScanPurchase purchase = new ScanPurchase(purchaseId, email.trim(), plan, reference);
        purchase.setAkwapayIntentId(intentId);
        scanPurchaseRepository.save(purchase);

        recordPending(reference, intentId, purchaseId, email);

        log.info("initPurchase: intent='{}' status='{}' purchaseId='{}'",
                intentId, akwapayResponse.get("status"), purchaseId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purchaseId", purchaseId);
        result.put("akwapay", akwapayResponse);
        return result;
    }

    private void recordPending(String reference, String intentId, String purchaseId, String email) {
        try {
            pendingIntents.save(new AkwaPayPendingScanIntent(
                    reference, intentId, purchaseId, email, Instant.now(), null));
            log.info("recordPending: ref='{}' intent='{}' purchaseId='{}'", reference, intentId, purchaseId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' purchaseId='{}' — " +
                            "this purchase can now only move to APPROVED via webhook or by hand.",
                    reference, intentId, purchaseId, e);
        }
    }

    // ─── Status probe ─────────────────────────────────────────────────────

    public Map<String, Object> status(String intentId) {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intentId)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).map(body -> {
                    log.error("scan status probe error: status={} body={}", r.statusCode(), body);
                    return new RuntimeException("AkwaPay returned " + r.statusCode());
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");
        return result;
    }

    // ─── Webhook handling ─────────────────────────────────────────────────

    public boolean handleWebhook(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("scan webhook: missing signature header");
            return false;
        }
        if (!verifySignature(rawBody, signatureHeader)) {
            log.warn("scan webhook: invalid signature");
            return false;
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("type"));
            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("scan webhook: ignoring event type='{}'", eventType);
                return true;
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("scan webhook: no data block");
                return true;
            }

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || !reference.startsWith(REF_PREFIX_SCAN)) {
                log.info("scan webhook: reference '{}' not ours (intent='{}') — ignoring", reference, intentId);
                return true;
            }

            var pending = pendingIntents.findByReference(reference);
            if (pending.isEmpty()) {
                log.warn("scan webhook: no pending row for ref='{}' intent='{}' — already settled or lost",
                        reference, intentId);
                return true;
            }

            settlePurchase(pending.get());
            pendingIntents.deleteByReference(reference);

        } catch (Exception e) {
            log.error("scan webhook: unexpected error processing payload", e);
            return true;
        }

        return true;
    }

    // ─── Reconciliation sweep ─────────────────────────────────────────────
    //
    // Primary settlement path — see the RELIABILITY GUARANTEE note above the
    // class. Webhooks are treated as a nice-to-have, not load-bearing.

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingScanIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);
        var stale = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        var due = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;

        log.info("scan reconcile: {} of {} pending intent(s) due this tick", due.size(), stale.size());

        for (var intent : due) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.error("scan reconcile: unexpected error for ref='{}' intent='{}' — will retry next tick",
                        intent.getReference(), intent.getIntentId(), e);
            }
        }
    }

    private boolean isDue(AkwaPayPendingScanIntent intent, Instant now) {
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingScanIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingScanIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("scan reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            pendingIntents.deleteByReference(ref);
            return;
        }

        intent.markChecked(Instant.now());

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intent.getIntentId())
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).map(body -> {
                    log.error("scan reconcile: AkwaPay status check error ref='{}' status={} body={}",
                            ref, r.statusCode(), body);
                    return new RuntimeException("AkwaPay returned " + r.statusCode());
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> {
                    log.warn("scan reconcile: status check failed ref='{}' intent='{}' — retrying next sweep: {}",
                            ref, intent.getIntentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) return;

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("scan reconcile: ref='{}' intent='{}' akwapayStatus='{}'", ref, intent.getIntentId(), akwapayStatus);

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("scan reconcile: ref='{}' succeeded on sweep — approving purchase now", ref);
                settlePurchase(intent);
                pendingIntents.deleteByReference(ref);
            }
            case "failed", "declined", "cancelled", "expired" -> {
                log.warn("scan reconcile: ref='{}' intent='{}' terminal status='{}' — purchase stays AWAITING_PAYMENT",
                        ref, intent.getIntentId(), akwapayStatus);
                pendingIntents.deleteByReference(ref);
            }
            default -> log.info("scan reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                    ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    /**
     * Approves the purchase and records referral commission. Idempotent:
     * only acts if the purchase is still AWAITING_PAYMENT.
     *
     * Note: CommissionService.recordIfReferred is typed to Plan, not
     * ScanPlan, so null is passed for the plan argument — it's stored as-is
     * on the CommissionRecord and not branched on inside CommissionService.
     * If CommissionRecord.getPlan() is read anywhere downstream, that call
     * site needs a null-check for AKWAPAY_SCAN_ records.
     */
    private synchronized void settlePurchase(AkwaPayPendingScanIntent intent) {
        var purchaseOpt = scanPurchaseRepository.findById(intent.getPurchaseId());
        if (purchaseOpt.isEmpty()) {
            log.error("settlePurchase: no ScanPurchase found for purchaseId='{}' ref='{}' — cannot approve",
                    intent.getPurchaseId(), intent.getReference());
            return;
        }

        var purchase = purchaseOpt.get();
        if (purchase.getStatus() != ScanPurchaseStatus.AWAITING_PAYMENT) {
            log.warn("settlePurchase: purchaseId='{}' already '{}' — skipping (duplicate settlement)",
                    purchase.getId(), purchase.getStatus());
            return;
        }

        purchase.setStatus(ScanPurchaseStatus.APPROVED);
        purchase.setReviewedAt(LocalDateTime.now());
        purchase.setReviewedByAdmin("akwapay-auto");
        scanPurchaseRepository.save(purchase);

        try {
            UserAccount account = accountService.getAccountOrNull(purchase.getEmail());
            String referredByAdminCode = account == null ? null : account.getReferredByAdminCode();

            commissionService.recordIfReferred(
                    "AKWAPAY_SCAN_" + purchase.getId(), purchase.getEmail(), null,
                    purchase.getScanPlan().getAmountCedis(), referredByAdminCode);
        } catch (Exception e) {
            log.error("settlePurchase: commission attribution failed for purchaseId='{}'", purchase.getId(), e);
        }

        log.info("settlePurchase: purchaseId='{}' email='{}' plan='{}' auto-approved via AkwaPay ref='{}'",
                purchase.getId(), purchase.getEmail(), purchase.getScanPlan().name(), intent.getReference());
    }

    // ─── Network resolution ─────────────────────────────────────────────────

    private String resolveNetwork(String requested, String phone) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        var detected = detectNetworkFromPhone(phone);
        if (detected.isPresent()) return detected.get();

        throw new ApiException(
                "We couldn't tell which network that number is on. Please choose MTN, Telecel, or AirtelTigo.",
                HttpStatus.BAD_REQUEST);
    }

    private Optional<String> detectNetworkFromPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        var digits = phone.replaceAll("\\D", "");

        String local;
        if (digits.startsWith("233") && digits.length() == 12) {
            local = "0" + digits.substring(3);
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            local = digits;
        } else {
            return Optional.empty();
        }
        return Optional.ofNullable(GH_NETWORK_PREFIXES.get(local.substring(0, 3)));
    }

    // ─── AkwaPay API helper ───────────────────────────────────────────────

    /**
     * Builds a Flutterwave-v4-unique customer email via plus-addressing so
     * POST /customers never sees the same address twice while a prior
     * intent for this user is still unresolved. See the class-level
     * "WHY customer.email IS SYNTHETIC" note.
     */
    private String syntheticEmail(String email, String reference) {
        if (email != null && email.contains("@")) {
            int atIdx = email.indexOf("@");
            return email.substring(0, atIdx)
                    + "+" + System.currentTimeMillis()
                    + email.substring(atIdx);
        }
        return reference.replaceAll("[^a-zA-Z0-9]", "")
                + System.currentTimeMillis()
                + "@customers.akwapay.com";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas, String reference, String email,
                                                    String phone, String network, String returnUrl,
                                                    Map<String, Object> metadata) {
        var syntheticEmail = syntheticEmail(email, reference);
        log.info("akwapayCreateIntent: ref='{}' syntheticEmail='{}'", reference, syntheticEmail);

        var customer = new HashMap<String, Object>();
        customer.put("email", syntheticEmail);
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount", amountPesewas);
        body.put("currency", "GHS");
        body.put("reference", reference);
        body.put("return_url", returnUrl);
        body.put("metadata", metadata);
        body.put("customer", customer);
        body.put("method", "mobile_money");
        body.put("network", network.toUpperCase());

        var idempotencyKey = UUID.randomUUID().toString();

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/payment_intents")
                .header("Authorization", "Bearer " + secretKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .map(errBody -> {
                            log.error("AkwaPay API error: status={} ref='{}' body={}",
                                    clientResponse.statusCode(), reference, errBody);
                            return new RuntimeException(
                                    "AkwaPay returned " + clientResponse.statusCode() + ": " + errBody);
                        }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .retryWhen(Retry.max(akwapayRetryLimit)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("AkwaPay API unreachable after {} retries", akwapayRetryLimit, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        })
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");

        var status = String.valueOf(result.get("status"));
        if (result.get("error") != null) {
            log.error("akwapayCreateIntent: error on ref='{}' — {}", reference, result.get("error"));
            throw new RuntimeException("AkwaPay error: " + result.get("error"));
        }
        if ("failed".equals(status)) {
            log.error("akwapayCreateIntent: intent created but already failed, ref='{}'", reference);
            throw new RuntimeException("Payment could not be started. Please try again.");
        }

        return result;
    }

    // ─── Reference encoding ───────────────────────────────────────────────
    //
    // sbscn-<32-hex nonce>-<8-hex nonce>. Hyphens only — Flutterwave rejects
    // underscores in tx_ref. Purely a unique+prefixed token — routing back
    // to a ScanPurchase happens via the in-memory pendingIntents map keyed
    // on the full reference.

    private String buildReference() {
        var nonceSource = UUID.randomUUID().toString().replace("-", "");
        return REF_PREFIX_SCAN + nonceSource.substring(0, 32) + "-" + nonceSource.substring(32, 40);
    }

    // ─── Signature verification ───────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String header) {
        try {
            String t = null, v1 = null;
            for (var part : header.split(",")) {
                var kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0])) t = kv[1].trim();
                else if ("v1".equals(kv[0])) v1 = kv[1].trim();
            }
            if (t == null || v1 == null) {
                log.warn("scan webhook: malformed signature header");
                return false;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(t);
            } catch (NumberFormatException e) {
                log.warn("scan webhook: non-numeric timestamp");
                return false;
            }

            var age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > SIGNATURE_TOLERANCE.toSeconds()) {
                log.warn("scan webhook: signature timestamp {}s old — replay rejected", age);
                return false;
            }

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);

            var expected = HexFormat.of().formatHex(mac.doFinal());
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("scan webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}