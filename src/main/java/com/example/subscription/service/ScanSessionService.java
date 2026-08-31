package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.*;
import com.example.subscription.repository.*;
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
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@EnableScheduling
public class ScanSessionService {

    private static final Logger log = LoggerFactory.getLogger(ScanSessionService.class);

    private static final String REF_PREFIX = "scsn-";

    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration SWEEP_HEAD_START    = Duration.ofSeconds(5);
    private static final Duration TIER_HOT_UNTIL      = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL     = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL     = Duration.ofMinutes(60);
    private static final Duration POLL_EVERY_HOT      = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM     = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL     = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD     = Duration.ofMinutes(10);
    private static final Duration ABANDON_AFTER       = Duration.ofHours(24);

    private static final Map<String, String> GH_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024","025","053","054","055","059"}) GH_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020","050"})                         GH_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026","027","056","057"})             GH_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final InMemoryScanSessionRepository                        sessionRepo;
    private final InMemoryAkwaPayPendingScanSessionIntentRepository    pendingIntents;
    private final NvidiaAiService                                      nvidiaAiService;
    private final CommissionService                                    commissionService;
    private final AccountService                                       accountService;
    private final org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;
    private final ObjectMapper                                         objectMapper;

    @Value("${app.akwapay.secret-key}")                   private String secretKey;
    @Value("${app.akwapay.webhook-secret.scan-session}")  private String webhookSecret;
    @Value("${app.akwapay.base-url}")                     private String baseUrl;
    @Value("${app.platform.frontend-url}")                private String frontendUrl;

    private final Duration akwapayTimeout    = Duration.ofSeconds(15);
    private final long     akwapayRetryLimit = 2;

    public ScanSessionService(
            InMemoryScanSessionRepository sessionRepo,
            InMemoryAkwaPayPendingScanSessionIntentRepository pendingIntents,
            NvidiaAiService nvidiaAiService,
            CommissionService commissionService,
            AccountService accountService,
            org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.sessionRepo      = sessionRepo;
        this.pendingIntents   = pendingIntents;
        this.nvidiaAiService  = nvidiaAiService;
        this.commissionService = commissionService;
        this.accountService   = accountService;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper     = objectMapper;
    }

    // ── List plans ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> listPlans() {
        return Arrays.stream(ScanSessionPlan.values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code",            p.getCode());
            m.put("name",            p.name());
            m.put("amountCedis",     p.getAmountCedis());
            m.put("durationMinutes", p.getDurationMinutes());
            return m;
        }).toList();
    }

    // ── Init payment ───────────────────────────────────────────────────────

    public Map<String, Object> initSession(String email, String planCode,
                                           String phone, String requestedNetwork) {
        if (email == null || email.isBlank())
            throw new ApiException("email is required", HttpStatus.BAD_REQUEST);

        boolean inFlight = sessionRepo.findByEmail(email).stream()
                .anyMatch(s -> s.getStatus() == ScanSession.Status.AWAITING_PAYMENT
                            || s.getStatus() == ScanSession.Status.ACTIVE);
        if (inFlight)
            throw new ApiException(
                "You already have an active scan session or one awaiting payment. " +
                "Wait for it to expire before purchasing another.",
                HttpStatus.CONFLICT);

        ScanSessionPlan plan    = ScanSessionPlan.fromCode(planCode);
        String          network = resolveNetwork(requestedNetwork, phone);
        String          sessionId  = CodeGenerator.generateId();
        String          reference  = buildReference();
        int             amountPesewas = plan.getAmountCedis() * 100;

        log.info("initSession email='{}' plan='{}' amountPesewas={} ref='{}' sessionId='{}'",
                email, plan.name(), amountPesewas, reference, sessionId);

        var akwapayResp = akwapayCreateIntent(
                amountPesewas, reference, email, phone, network,
                frontendUrl + "/scan?session=success",
                Map.of("sessionId", sessionId, "purpose", "scan_session", "realEmail", email.trim()));

        ScanSession session = new ScanSession(sessionId, email.trim(), plan, reference);
        session.setAkwapayIntentId(String.valueOf(akwapayResp.get("id")));
        sessionRepo.save(session);

        pendingIntents.save(new AkwaPayPendingScanSessionIntent(
                reference, String.valueOf(akwapayResp.get("id")), sessionId, email, Instant.now(), null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("akwapay",   akwapayResp);
        return out;
    }

    // ── Status ─────────────────────────────────────────────────────────────

    public Map<String, Object> sessionStatus(String sessionId) {
        ScanSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ApiException("Session not found", HttpStatus.NOT_FOUND));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId",       s.getId());
        out.put("status",          s.getStatus().name());
        out.put("plan",            s.getPlan().name());
        out.put("durationMinutes", s.getPlan().getDurationMinutes());
        out.put("amountCedis",     s.getPlan().getAmountCedis());
        out.put("scanCount",       s.getScanCount());
        out.put("activatedAt",     s.getActivatedAt());
        out.put("expiresAt",       s.getExpiresAt());
        out.put("secondsRemaining", s.secondsRemaining());
        return out;
    }

    // ── Analyze ────────────────────────────────────────────────────────────

    public Map<String, Object> analyze(String sessionId, String email,
                                       byte[] imageBytes, String mimeType) {
        ScanSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ApiException("Scan session not found", HttpStatus.NOT_FOUND));

        if (!session.getEmail().equalsIgnoreCase(email))
            throw new ApiException("Email does not match this session", HttpStatus.FORBIDDEN);

        if (session.getStatus() == ScanSession.Status.AWAITING_PAYMENT)
            throw new ApiException("Payment not confirmed yet — approve the MoMo prompt first.",
                    HttpStatus.PAYMENT_REQUIRED);

        if (session.getStatus() == ScanSession.Status.FAILED)
            throw new ApiException("Payment failed. Please start a new session.",
                    HttpStatus.PAYMENT_REQUIRED);

        // Lazily expire if timer ran out
        if (session.getStatus() == ScanSession.Status.ACTIVE && session.isExpired()) {
            session.markExpired();
            sessionRepo.save(session);
        }

        if (session.getStatus() == ScanSession.Status.EXPIRED)
            throw new ApiException(
                "Your scan session has expired. Purchase a new session to continue.",
                HttpStatus.GONE);

        if (!session.canScan())
            throw new ApiException("Session is not active", HttpStatus.GONE);

        log.info("analyze sessionId='{}' email='{}' plan='{}' scanCount={} secondsRemaining={}",
                sessionId, email, session.getPlan().name(),
                session.getScanCount(), session.secondsRemaining());

        // All sessions get full-coverage analysis (same as PREMIUM ScanPlan)
        String imageBase64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        NvidiaAiService.ScanAnalysis analysis =
                nvidiaAiService.analyzeSlip(imageBase64, mimeType, ScanPlan.PREMIUM);

        session.incrementScanCount();
        sessionRepo.save(session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId",        session.getId());
        out.put("plan",             session.getPlan().name());
        out.put("scanCount",        session.getScanCount());
        out.put("secondsRemaining", session.secondsRemaining());
        out.put("expiresAt",        session.getExpiresAt());
        out.put("totalPicksDetected", analysis.totalPicksDetected);
        out.put("picksAnalyzed",    analysis.predictions.size());
        out.put("predictions",      analysis.predictions);
        if (analysis.rawModelOutput != null) out.put("rawModelOutput", analysis.rawModelOutput);
        return out;
    }

    // ── Activation (webhook + reconcile both call this) ────────────────────

    public synchronized void activate(AkwaPayPendingScanSessionIntent intent) {
        var opt = sessionRepo.findById(intent.getSessionId());
        if (opt.isEmpty()) {
            log.error("activate: no ScanSession for sessionId='{}' ref='{}'",
                    intent.getSessionId(), intent.getReference());
            return;
        }
        ScanSession s = opt.get();
        if (s.getStatus() != ScanSession.Status.AWAITING_PAYMENT) {
            log.warn("activate: sessionId='{}' already '{}' — skipping", s.getId(), s.getStatus());
            return;
        }
        s.activate();
        sessionRepo.save(s);

        try {
            var account = accountService.getAccountOrNull(s.getEmail());
            String refCode = account == null ? null : account.getReferredByAdminCode();
            commissionService.recordIfReferred(
                    "AKWAPAY_SCAN_SESSION_" + s.getId(),
                    s.getEmail(), null, s.getPlan().getAmountCedis(), refCode);
        } catch (Exception e) {
            log.error("activate: commission failed for sessionId='{}'", s.getId(), e);
        }

        log.info("activate: sessionId='{}' email='{}' plan='{}' expiresAt='{}'",
                s.getId(), s.getEmail(), s.getPlan().name(), s.getExpiresAt());
    }

    // ── Webhook ────────────────────────────────────────────────────────────

    public boolean handleWebhook(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) return false;
        if (!verifySignature(rawBody, signatureHeader)) return false;
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
            if (!"payment_intent.succeeded".equals(String.valueOf(event.get("type")))) return true;

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) return true;

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            if (reference == null || !reference.startsWith(REF_PREFIX)) return true;

            var pending = pendingIntents.findByReference(reference);
            if (pending.isEmpty()) return true;

            activate(pending.get());
            pendingIntents.deleteByReference(reference);
        } catch (Exception e) {
            log.error("scan-session webhook: error", e);
        }
        return true;
    }

    // ── Reconciliation sweep ───────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);
        var stale  = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        var due    = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;
        log.info("scan-session reconcile: {} due", due.size());
        for (var intent : due) {
            try { reconcileOne(intent); }
            catch (Exception e) {
                log.error("scan-session reconcile: error for ref='{}'", intent.getReference(), e);
            }
        }
    }

    // ── Expiry sweep ───────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${session.cleanup.interval-ms:30000}")
    public void expireStaleSessions() {
        for (ScanSession s : sessionRepo.findActive()) {
            if (s.isExpired()) {
                s.markExpired();
                sessionRepo.save(s);
                log.info("expireStaleSessions: sessionId='{}' expired", s.getId());
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private boolean isDue(AkwaPayPendingScanSessionIntent i, Instant now) {
        var last = i.getLastCheckedAt();
        return last == null || last.plus(pollIntervalFor(i, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingScanSessionIntent i, Instant now) {
        var age = Duration.between(i.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingScanSessionIntent intent) {
        var ref = intent.getReference();
        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("scan-session reconcile: abandoning ref='{}'", ref);
            pendingIntents.deleteByReference(ref);
            return;
        }
        intent.markChecked(Instant.now());

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intent.getIntentId())
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).map(b -> {
                    log.error("reconcile error ref='{}' status={}", ref, r.statusCode());
                    return new RuntimeException("AkwaPay returned " + r.statusCode());
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> { log.warn("reconcile failed ref='{}': {}", ref, e.getMessage()); return Mono.empty(); })
                .block();

        if (result == null) return;
        var akStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("reconcile: ref='{}' akwapayStatus='{}'", ref, akStatus);

        switch (akStatus) {
            case "succeeded" -> { activate(intent); pendingIntents.deleteByReference(ref); }
            case "failed","declined","cancelled","expired" -> {
                sessionRepo.findByAkwapayReference(ref).ifPresent(s -> { s.markFailed(); sessionRepo.save(s); });
                pendingIntents.deleteByReference(ref);
            }
        }
    }

    private String resolveNetwork(String requested, String phone) {
        if (requested != null && !requested.isBlank()) return requested.trim().toUpperCase();
        return detectNetwork(phone).orElseThrow(() ->
                new ApiException("Couldn't detect network. Please choose MTN, Telecel, or AirtelTigo.",
                        HttpStatus.BAD_REQUEST));
    }

    private Optional<String> detectNetwork(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        var digits = phone.replaceAll("\\D", "");
        String local;
        if (digits.startsWith("233") && digits.length() == 12) local = "0" + digits.substring(3);
        else if (digits.length() == 10 && digits.startsWith("0")) local = digits;
        else return Optional.empty();
        return Optional.ofNullable(GH_PREFIXES.get(local.substring(0, 3)));
    }

    private String syntheticEmail(String email, String reference) {
        if (email != null && email.contains("@")) {
            int at = email.indexOf("@");
            return email.substring(0, at) + "+" + System.currentTimeMillis() + email.substring(at);
        }
        return reference.replaceAll("[^a-zA-Z0-9]", "") + System.currentTimeMillis() + "@customers.akwapay.com";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas, String reference,
                                                     String email, String phone, String network,
                                                     String returnUrl, Map<String, Object> metadata) {
        var customer = new HashMap<String, Object>();
        customer.put("email", syntheticEmail(email, reference));
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",    amountPesewas);
        body.put("currency",  "GHS");
        body.put("reference", reference);
        body.put("return_url", returnUrl);
        body.put("metadata",  metadata);
        body.put("customer",  customer);
        body.put("method",    "mobile_money");
        body.put("network",   network.toUpperCase());

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/payment_intents")
                .header("Authorization", "Bearer " + secretKey)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, cr -> cr.bodyToMono(String.class).map(err -> {
                    log.error("AkwaPay error ref='{}': {}", reference, err);
                    return new RuntimeException("AkwaPay returned " + cr.statusCode() + ": " + err);
                }))
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .retryWhen(Retry.max(akwapayRetryLimit)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");
        if (result.get("error") != null) throw new RuntimeException("AkwaPay error: " + result.get("error"));
        if ("failed".equals(String.valueOf(result.get("status"))))
            throw new RuntimeException("Payment could not be started. Please try again.");
        return result;
    }

    private String buildReference() {
        var src = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        return REF_PREFIX + src.substring(0, 32) + "-" + src.substring(32, 40);
    }

    private boolean verifySignature(byte[] rawBody, String header) {
        try {
            String t = null, v1 = null;
            for (var part : header.split(",")) {
                var kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0]))  t  = kv[1].trim();
                if ("v1".equals(kv[0])) v1 = kv[1].trim();
            }
            if (t == null || v1 == null) return false;
            long ts;
            try { ts = Long.parseLong(t); } catch (NumberFormatException e) { return false; }
            if (Math.abs(Instant.now().getEpochSecond() - ts) > SIGNATURE_TOLERANCE.toSeconds()) return false;

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            var expected = HexFormat.of().formatHex(mac.doFinal());
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { log.error("signature verification threw", e); return false; }
    }
}