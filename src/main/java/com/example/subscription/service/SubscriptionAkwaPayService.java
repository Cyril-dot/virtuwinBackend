package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.AkwaPayPayment;
import com.example.subscription.model.AkwaPayPaymentStatus;
import com.example.subscription.model.AkwaPayPendingSubscriptionIntent;
import com.example.subscription.model.Plan;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryAkwaPayPaymentRepository;
import com.example.subscription.repository.InMemoryAkwaPayPendingSubscriptionIntentRepository;
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
 * AkwaPay integration for subscription payments, as an alternative to
 * {@link ManualPaymentService}'s screenshot-and-admin-review flow.
 *
 * Ported from the same AkwaPay pattern used for scan purchases
 * (ScanPurchaseAkwaPayService) and originally from the wallet-deposit
 * AkwaPay controller. Reference encoding, the two-secret signature scheme,
 * the tiered reconciliation sweep, and treating "unknown" as in-flight
 * rather than a failure are all identical in spirit — see those classes for
 * the full rationale. Only what's specific to subscriptions is covered
 * below.
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
 *     sbsub-<32-hex nonce>-<8-hex nonce>     subscription payment
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
 * sweep ({@link #reconcilePendingSubscriptionIntents}) as the primary
 * settlement mechanism. Both paths dedupe via {@link #settlePayment}
 * checking that the payment is still AWAITING_PAYMENT, so no double-approval
 * is possible whichever wins the race.
 *
 * WHAT A SUCCESSFUL CHARGE DOES: exactly what ManualPaymentService.approve()
 * does on manual approval — accountService.assignSubscription(email, plan)
 * and commissionService.recordIfReferred(...) — except triggered by AkwaPay
 * confirming payment instead of an admin click. There is no admin review
 * step on this path. The generated password is revealed exactly once, on
 * the first status check after approval, via {@link #checkStatus} — same
 * contract as ManualPaymentService.checkStatus.
 *
 * IDEMPOTENCY: enforced by checking the AkwaPayPayment is still
 * AWAITING_PAYMENT before settling it (see {@link #settlePayment}), the same
 * way ScanPurchaseAkwaPayService guards against a double settlement when the
 * webhook and the sweep race.
 *
 * PENDING LEDGER: in-memory (see AkwaPayPendingSubscriptionIntent's doc for
 * the accepted restart-loss trade-off, identical to the scan-purchase
 * version).
 */
@Service
@EnableScheduling // harmless if another config class already enables it; Spring dedupes
public class SubscriptionAkwaPayService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAkwaPayService.class);

    // Hyphens only — Flutterwave rejects underscores in tx_ref.
    private static final String REF_PREFIX_SUB = "sbsub-";

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

    private final InMemoryAkwaPayPaymentRepository                    paymentRepository;
    private final InMemoryAkwaPayPendingSubscriptionIntentRepository  pendingIntents;
    private final AccountService                                      accountService;
    private final CommissionService                                   commissionService;
    private final WebClient.Builder                                   webClientBuilder;
    private final ObjectMapper                                        objectMapper;

    @Value("${app.akwapay.secret-key}")     private String secretKey;
    @Value("${app.akwapay.webhook-secret}") private String webhookSecret;
    @Value("${app.akwapay.base-url}")       private String baseUrl;
    @Value("${app.platform.frontend-url}")  private String frontendUrl;

    private final Duration akwapayTimeout    = Duration.ofSeconds(15);
    private final long     akwapayRetryLimit = 2;

    public SubscriptionAkwaPayService(InMemoryAkwaPayPaymentRepository paymentRepository,
                                      InMemoryAkwaPayPendingSubscriptionIntentRepository pendingIntents,
                                      AccountService accountService,
                                      CommissionService commissionService,
                                      WebClient.Builder webClientBuilder,
                                      ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.pendingIntents = pendingIntents;
        this.accountService = accountService;
        this.commissionService = commissionService;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    // ─── Init ───────────────────────────────────────────────────────────────

    /**
     * Creates the AkwaPayPayment (status=AWAITING_PAYMENT) and the matching
     * AkwaPay intent, and records the pending row so the sweep can find it
     * even if the webhook never arrives.
     *
     * Reuses AccountService.canPurchase - same "already registered, no
     * active subscription" gate ManualPaymentService.submit() uses - plus a
     * check that this email has no other AkwaPay payment still
     * AWAITING_PAYMENT, so a user can't spin up five intents in a row.
     */
    public Map<String, Object> initPayment(String email, String planCode, String phone, String requestedNetwork) {
        if (email == null || email.isBlank()) {
            throw new ApiException("email is required", HttpStatus.BAD_REQUEST);
        }

        if (!accountService.canPurchase(email)) {
            throw new ApiException(
                    "Cannot start payment: either " + email + " is not registered yet, " +
                            "or it already has an active subscription (one at a time).",
                    HttpStatus.CONFLICT);
        }

        boolean inFlight = paymentRepository.findByEmail(email).stream()
                .anyMatch(p -> p.getStatus() == AkwaPayPaymentStatus.AWAITING_PAYMENT);
        if (inFlight) {
            throw new ApiException(
                    "You already have an AkwaPay payment awaiting confirmation. " +
                            "Wait for it to settle, or contact support if it's stuck.",
                    HttpStatus.CONFLICT);
        }

        Plan plan = Plan.fromCode(planCode);
        var network = resolveNetwork(requestedNetwork, phone);

        String paymentId = CodeGenerator.generateId();
        String reference = buildReference();

        // Plan.getAmountCedis() returns a plain int (whole cedis), same as
        // ScanPlan - no BigDecimal scaling needed, just multiply for pesewas.
        int amountPesewas = plan.getAmountCedis() * 100;

        log.info("initPayment: email='{}' plan='{}' amountPesewas={} ref='{}' paymentId='{}'",
                email, plan.name(), amountPesewas, reference, paymentId);

        var akwapayResponse = akwapayCreateIntent(
                amountPesewas, reference, email, phone, network,
                frontendUrl + "/subscribe?payment=success",
                Map.of("paymentId", paymentId, "purpose", "subscription", "realEmail", email.trim()));

        var intentId = String.valueOf(akwapayResponse.get("id"));

        AkwaPayPayment payment = new AkwaPayPayment(paymentId, email.trim(), plan, reference);
        payment.setIntentId(intentId);
        paymentRepository.save(payment);

        recordPending(reference, intentId, paymentId, email);

        log.info("initPayment: intent='{}' status='{}' paymentId='{}'",
                intentId, akwapayResponse.get("status"), paymentId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", paymentId);
        result.put("akwapay", akwapayResponse);
        return result;
    }

    private void recordPending(String reference, String intentId, String paymentId, String email) {
        try {
            pendingIntents.save(new AkwaPayPendingSubscriptionIntent(
                    reference, intentId, paymentId, email, Instant.now(), null));
            log.info("recordPending: ref='{}' intent='{}' paymentId='{}' — sweep will reconcile if webhook is lost",
                    reference, intentId, paymentId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' paymentId='{}' — " +
                            "this payment can now only move to APPROVED via webhook or by hand. Investigate.",
                    reference, intentId, paymentId, e);
        }
    }

    // ─── Lookups / status (app-level, not AkwaPay's own status) ─────────────

    public AkwaPayPayment getById(String id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ApiException("AkwaPay payment not found", HttpStatus.NOT_FOUND));
    }

    /**
     * User-facing status check, mirroring ManualPaymentService.checkStatus
     * exactly: the generated password is revealed exactly once, on the
     * first status check after approval.
     */
    public synchronized StatusResult checkStatus(String id) {
        AkwaPayPayment payment = getById(id);

        String password = null;
        if (payment.getStatus() == AkwaPayPaymentStatus.APPROVED && !payment.isPasswordRevealed()) {
            UserAccount account = accountService.getAccountOrNull(payment.getEmail());
            if (account != null && account.getPassword() != null) {
                password = account.getPassword();
                payment.setPasswordRevealed(true);
                paymentRepository.save(payment);
            }
        }

        return new StatusResult(payment, password);
    }

    /** Small holder so the controller can access both the record and the (maybe-null) revealed password. */
    public static class StatusResult {
        public final AkwaPayPayment payment;
        public final String password; // non-null only on the one call where it's first revealed

        public StatusResult(AkwaPayPayment payment, String password) {
            this.payment = payment;
            this.password = password;
        }
    }

    // ─── AkwaPay's own status probe (read-only) ──────────────────────────────

    public Map<String, Object> akwapayIntentStatus(String intentId) {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intentId)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).map(body -> {
                    log.error("subscription status probe error: status={} body={}", r.statusCode(), body);
                    return new RuntimeException("AkwaPay returned " + r.statusCode());
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");
        return result;
    }

    // ─── Webhook handling ────────────────────────────────────────────────────

    public boolean handleWebhook(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("subscription webhook: missing signature header");
            return false;
        }
        if (!verifySignature(rawBody, signatureHeader)) {
            log.warn("subscription webhook: invalid signature");
            return false;
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("type"));
            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("subscription webhook: ignoring event type='{}'", eventType);
                return true;
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("subscription webhook: no data block");
                return true;
            }

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || !reference.startsWith(REF_PREFIX_SUB)) {
                log.info("subscription webhook: reference '{}' not ours (intent='{}') — ignoring", reference, intentId);
                return true;
            }

            var pending = pendingIntents.findByReference(reference);
            if (pending.isEmpty()) {
                log.warn("subscription webhook: no pending row for ref='{}' intent='{}' — already settled or lost " +
                        "(e.g. restart). If a payment is stuck in AWAITING_PAYMENT for this reference, " +
                        "approve it by hand.", reference, intentId);
                return true;
            }

            settlePayment(pending.get());
            pendingIntents.deleteByReference(reference);

        } catch (Exception e) {
            log.error("subscription webhook: unexpected error processing payload", e);
            return true;
        }

        return true;
    }

    // ─── Reconciliation sweep ────────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingSubscriptionIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);
        var stale = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        var due = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;

        log.info("subscription reconcile: {} of {} pending intent(s) due this tick", due.size(), stale.size());

        for (var intent : due) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.error("subscription reconcile: unexpected error for ref='{}' intent='{}' — will retry next tick",
                        intent.getReference(), intent.getIntentId(), e);
            }
        }
    }

    private boolean isDue(AkwaPayPendingSubscriptionIntent intent, Instant now) {
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingSubscriptionIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingSubscriptionIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("subscription reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            markFailedIfStillAwaiting(intent, "abandoned after " + ABANDON_AFTER.toHours() + "h with no settlement");
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
                    log.error("subscription reconcile: AkwaPay status check error ref='{}' status={} body={}",
                            ref, r.statusCode(), body);
                    return new RuntimeException("AkwaPay returned " + r.statusCode());
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> {
                    log.warn("subscription reconcile: status check failed ref='{}' intent='{}' — retrying next sweep: {}",
                            ref, intent.getIntentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) return;

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("subscription reconcile: ref='{}' intent='{}' akwapayStatus='{}'",
                ref, intent.getIntentId(), akwapayStatus);

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("subscription reconcile: ref='{}' succeeded on sweep — approving payment now", ref);
                settlePayment(intent);
                pendingIntents.deleteByReference(ref);
            }
            case "failed", "declined", "cancelled", "expired" -> {
                log.warn("subscription reconcile: ref='{}' intent='{}' terminal status='{}' — marking FAILED",
                        ref, intent.getIntentId(), akwapayStatus);
                markFailedIfStillAwaiting(intent, "AkwaPay reported " + akwapayStatus);
                pendingIntents.deleteByReference(ref);
            }
            default -> log.info("subscription reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                    ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    private void markFailedIfStillAwaiting(AkwaPayPendingSubscriptionIntent intent, String reason) {
        var paymentOpt = paymentRepository.findById(intent.getPaymentId());
        if (paymentOpt.isEmpty()) return;

        var payment = paymentOpt.get();
        if (payment.getStatus() != AkwaPayPaymentStatus.AWAITING_PAYMENT) return;

        payment.setStatus(AkwaPayPaymentStatus.FAILED);
        payment.setFailureReason(reason);
        payment.setReviewedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        log.info("markFailedIfStillAwaiting: paymentId='{}' marked FAILED ({})", payment.getId(), reason);
    }

    /**
     * Moves the AkwaPayPayment from AWAITING_PAYMENT to APPROVED and assigns
     * the subscription + records referral commission, exactly like
     * ManualPaymentService.approve() does on manual admin approval. Only
     * acts if the payment is still AWAITING_PAYMENT, so a webhook arriving
     * after the sweep already settled it (or vice versa) is a silent no-op.
     */
    private synchronized void settlePayment(AkwaPayPendingSubscriptionIntent intent) {
        var paymentOpt = paymentRepository.findById(intent.getPaymentId());
        if (paymentOpt.isEmpty()) {
            log.error("settlePayment: no AkwaPayPayment found for paymentId='{}' ref='{}' — cannot approve",
                    intent.getPaymentId(), intent.getReference());
            return;
        }

        var payment = paymentOpt.get();
        if (payment.getStatus() != AkwaPayPaymentStatus.AWAITING_PAYMENT) {
            log.warn("settlePayment: paymentId='{}' already '{}' — skipping (duplicate settlement)",
                    payment.getId(), payment.getStatus());
            return;
        }

        UserAccount account = accountService.assignSubscription(payment.getEmail(), payment.getPlan());

        try {
            commissionService.recordIfReferred(
                    "AKWAPAY_" + payment.getId(), payment.getEmail(), payment.getPlan(),
                    payment.getPlan().getAmountCedis(), account.getReferredByAdminCode());
        } catch (Exception e) {
            log.error("settlePayment: commission attribution failed for paymentId='{}' — investigate",
                    payment.getId(), e);
        }

        payment.setStatus(AkwaPayPaymentStatus.APPROVED);
        payment.setReviewedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        log.info("settlePayment: paymentId='{}' email='{}' plan='{}' auto-approved via AkwaPay ref='{}'",
                payment.getId(), payment.getEmail(), payment.getPlan().name(), intent.getReference());
    }

    // ─── Network resolution ───────────────────────────────────────────────────

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

    // ─── AkwaPay API helper ───────────────────────────────────────────────────

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

    // ─── Reference encoding ───────────────────────────────────────────────────
    //
    // sbsub-<32-hex nonce>-<8-hex nonce>. Hyphens only — Flutterwave rejects
    // underscores in tx_ref. Purely a unique+prefixed token — routing back
    // to an AkwaPayPayment happens via the in-memory pendingIntents map
    // keyed on the full reference, same as the scan flow.

    private String buildReference() {
        var nonceSource = UUID.randomUUID().toString().replace("-", "");
        return REF_PREFIX_SUB + nonceSource.substring(0, 32) + "-" + nonceSource.substring(32, 40);
    }

    // ─── Signature verification ───────────────────────────────────────────────

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
                log.warn("subscription webhook: malformed signature header");
                return false;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(t);
            } catch (NumberFormatException e) {
                log.warn("subscription webhook: non-numeric timestamp");
                return false;
            }

            var age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > SIGNATURE_TOLERANCE.toSeconds()) {
                log.warn("subscription webhook: signature timestamp {}s old — replay rejected", age);
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
            log.error("subscription webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}