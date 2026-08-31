package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.*;
import com.example.subscription.repository.InMemoryScanResultRepository;
import com.example.subscription.repository.InMemoryScanSessionRepository;
import com.example.subscription.util.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Provides a payment-free virtual scan workflow exclusively for super admins.
 *
 * The super admin goes through every step a real user sees — plan selection,
 * "payment", session activation, image upload and AI scan — but no AkwaPay
 * call is ever made and no commission is recorded.
 *
 * Simulator sessions are stored in the same InMemoryScanSessionRepository but
 * are keyed with a synthetic email so they never pollute real-user queries.
 *
 * Lifecycle
 * ─────────
 *  1. createSimulatorSession(planCode) → returns a ScanSession in AWAITING_PAYMENT
 *  2. approveSimulatorPayment(sessionId) → transitions to ACTIVE (sets expiry)
 *  3. analyzeSlip(sessionId, imageBase64, mimeType) → calls NvidiaAiService,
 *     stores the result and increments scan count — unlimited within the window
 *  4. (optional) expireSession(sessionId) → manually expires
 */
@Service
public class SuperAdminScanSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminScanSimulatorService.class);

    /**
     * Synthetic email that marks every session created by the simulator.
     * Never matches a real user's email so history queries stay clean.
     */
    public static final String SIMULATOR_EMAIL = "superadmin-simulator@virtuwin.local";

    private final InMemoryScanSessionRepository sessionRepo;
    private final InMemoryScanResultRepository  resultRepo;
    private final NvidiaAiService               nvidiaAiService;

    public SuperAdminScanSimulatorService(
            InMemoryScanSessionRepository sessionRepo,
            InMemoryScanResultRepository  resultRepo,
            NvidiaAiService               nvidiaAiService) {
        this.sessionRepo     = sessionRepo;
        this.resultRepo      = resultRepo;
        this.nvidiaAiService = nvidiaAiService;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 1 — create a mock session
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Creates a simulator ScanSession in AWAITING_PAYMENT state.
     * No AkwaPay call is made; a synthetic reference is stored so the object
     * is structurally identical to a real one.
     */
    public ScanSession createSimulatorSession(String planCode) {
        ScanSessionPlan plan;
        try {
            plan = ScanSessionPlan.fromCode(planCode);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    "Invalid plan code '" + planCode + "'. Valid codes: SESSION_300, SESSION_500, SESSION_700",
                    HttpStatus.BAD_REQUEST);
        }

        String sessionId = "sim-" + CodeGenerator.generateId();
        String fakeRef   = "sim-ref-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ScanSession session = new ScanSession(sessionId, SIMULATOR_EMAIL, plan, fakeRef);
        // akwapayIntentId is left null — not needed for simulation
        sessionRepo.save(session);

        log.info("simulator: created session sessionId='{}' plan='{}'", sessionId, plan.name());
        return session;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 2 — simulate payment approval
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Instantly transitions the session from AWAITING_PAYMENT → ACTIVE,
     * setting the full-duration expiry window just as AkwaPay would.
     */
    public ScanSession approveSimulatorPayment(String sessionId) {
        ScanSession session = getSimulatorSession(sessionId);

        if (session.getStatus() != ScanSession.Status.AWAITING_PAYMENT) {
            throw new ApiException(
                    "Session is not in AWAITING_PAYMENT state (current: " + session.getStatus() + ")",
                    HttpStatus.CONFLICT);
        }

        session.activate();   // sets status=ACTIVE, activatedAt=now, expiresAt=now+plan.duration
        sessionRepo.save(session);

        log.info("simulator: approved payment sessionId='{}' expiresAt='{}'",
                sessionId, session.getExpiresAt());
        return session;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 3 — run AI scan (unlimited within window)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Runs the full NvidiaAiService pipeline exactly as a real user scan does.
     * The result is stored in InMemoryScanResultRepository under the simulator
     * email. Scan count is incremented on the session.
     *
     * @param sessionId    The simulator session returned by createSimulatorSession
     * @param imageBase64  Raw base-64 bytes of the slip image (no data-URI prefix)
     * @param mimeType     e.g. "image/jpeg", "image/png", "image/webp"
     * @return Full ScanResult including AI predictions
     */
    public ScanResult analyzeSlip(String sessionId, String imageBase64, String mimeType) {
        ScanSession session = getSimulatorSession(sessionId);

        if (!session.canScan()) {
            String reason = session.getStatus() == ScanSession.Status.AWAITING_PAYMENT
                    ? "Session payment has not been approved yet. Call /approve-payment first."
                    : session.getStatus() == ScanSession.Status.EXPIRED
                    ? "Session has expired. Create a new one."
                    : "Session is in status " + session.getStatus() + " and cannot scan.";
            throw new ApiException(reason, HttpStatus.CONFLICT);
        }

        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new ApiException("imageBase64 is required", HttpStatus.BAD_REQUEST);
        }

        String mime = mimeType == null ? "image/jpeg" : mimeType.toLowerCase().trim();
        if (!mime.matches("^image/(jpeg|png|webp)$")) {
            throw new ApiException(
                    "Unsupported image type '" + mime + "'. Allowed: image/jpeg, image/png, image/webp",
                    HttpStatus.BAD_REQUEST);
        }

        log.info("simulator: running AI scan sessionId='{}' plan='{}' scanCount={}",
                sessionId, session.getPlan().name(), session.getScanCount());

        // Mirror ScanSessionService.analyze() — use the session's ScanSessionPlan.
        // NvidiaAiService.analyzeSlip() expects a ScanPlan, so we map across.
        ScanPlan aiPlan = mapToScanPlan(session.getPlan());

        NvidiaAiService.ScanAnalysis analysis = nvidiaAiService.analyzeSlip(imageBase64, mime, aiPlan);

        // Build and persist the result
        ScanResult result = new ScanResult();
        result.setId(CodeGenerator.generateId());
        result.setPurchaseId(sessionId);          // reusing field to store session id
        result.setEmail(SIMULATOR_EMAIL);
        result.setScanPlan(aiPlan);
        result.setTotalPicksDetected(analysis.totalPicksDetected);
        result.setPredictions(analysis.predictions == null ? List.of() : analysis.predictions);
        result.setPicksAnalyzed(result.getPredictions().size());
        result.setRawModelOutput(analysis.rawModelOutput);
        result.setCoverageNote(buildCoverageNote(session.getPlan(), analysis));
        result.setCreatedAt(LocalDateTime.now());

        resultRepo.save(result);

        session.incrementScanCount();
        sessionRepo.save(session);

        log.info("simulator: scan complete sessionId='{}' picksDetected={} picksAnalyzed={}",
                sessionId, result.getTotalPicksDetected(), result.getPicksAnalyzed());
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers / queries
    // ──────────────────────────────────────────────────────────────────────

    public ScanSession getSimulatorSession(String sessionId) {
        return sessionRepo.findById(sessionId)
                .filter(s -> SIMULATOR_EMAIL.equals(s.getEmail()))
                .orElseThrow(() -> new ApiException(
                        "Simulator session not found: " + sessionId, HttpStatus.NOT_FOUND));
    }

    /** All simulator sessions, newest first. */
    public List<ScanSession> listSimulatorSessions() {
        return sessionRepo.findByEmail(SIMULATOR_EMAIL).stream()
                .sorted(Comparator.comparing(ScanSession::getCreatedAt).reversed())
                .toList();
    }

    /** All simulator scan results, newest first. */
    public List<ScanResult> listSimulatorResults() {
        return resultRepo.findByEmail(SIMULATOR_EMAIL).stream()
                .sorted(Comparator.comparing(ScanResult::getCreatedAt).reversed())
                .toList();
    }

    /** Manually expire a session (useful for demo resets). */
    public ScanSession expireSession(String sessionId) {
        ScanSession session = getSimulatorSession(sessionId);
        session.markExpired();
        sessionRepo.save(session);
        return session;
    }

    /** Describe the plan mapping for UI convenience. */
    public List<Map<String, Object>> listPlans() {
        return Arrays.stream(ScanSessionPlan.values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code",            p.getCode());
            m.put("name",            p.name());
            m.put("amountCedis",     p.getAmountCedis());
            m.put("durationMinutes", p.getDurationMinutes());
            m.put("label",           "GHS " + p.getAmountCedis());
            return m;
        }).toList();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Maps a ScanSessionPlan to the nearest ScanPlan for the AI service call.
     * BASIC→BASIC, STANDARD→STANDARD, PREMIUM→PREMIUM (full coverage, maxPicks=-1).
     */
    private ScanPlan mapToScanPlan(ScanSessionPlan p) {
        return switch (p) {
            case BASIC    -> ScanPlan.BASIC;
            case STANDARD -> ScanPlan.STANDARD;
            case PREMIUM  -> ScanPlan.PREMIUM;
        };
    }

    private String buildCoverageNote(ScanSessionPlan plan, NvidiaAiService.ScanAnalysis analysis) {
        int detected = analysis.totalPicksDetected;
        int analyzed = analysis.predictions == null ? 0 : analysis.predictions.size();
        return switch (plan) {
            case BASIC    -> analyzed + " of " + detected + " picks covered on the BASIC plan (10-min session).";
            case STANDARD -> analyzed + " of " + detected + " picks covered on the STANDARD plan (20-min session).";
            case PREMIUM  -> "Full coverage — all " + analyzed + " detected pick(s) analyzed (PREMIUM 40-min session).";
        };
    }
}