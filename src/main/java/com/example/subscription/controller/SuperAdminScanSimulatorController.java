package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.model.ScanResult;
import com.example.subscription.model.ScanSession;
import com.example.subscription.service.SuperAdminScanSimulatorService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Virtual scan simulator for super admins.
 *
 * Every endpoint is under /api/superadmin/simulator/** which means
 * AdminAuthFilter already enforces SUPER_ADMIN role — no extra auth needed.
 *
 * Full workflow
 * ─────────────
 *  POST  /api/superadmin/simulator/create-session      ← Step 1: pick a plan
 *  POST  /api/superadmin/simulator/approve-payment/{id} ← Step 2: instant approval (no charge)
 *  POST  /api/superadmin/simulator/scan/{id}            ← Step 3: upload image → AI predictions
 *  GET   /api/superadmin/simulator/sessions             ← list all simulator sessions
 *  GET   /api/superadmin/simulator/results              ← list all simulator scan results
 *  GET   /api/superadmin/simulator/plans                ← available plans for the UI dropdown
 *  POST  /api/superadmin/simulator/expire/{id}          ← manually expire a session
 */
@RestController
@RequestMapping("/api/superadmin/simulator")
public class SuperAdminScanSimulatorController {

    private final SuperAdminScanSimulatorService svc;

    public SuperAdminScanSimulatorController(SuperAdminScanSimulatorService svc) {
        this.svc = svc;
    }

    // ── Step 1: create session ────────────────────────────────────────────

    /**
     * Create a mock scan session for the chosen plan.
     *
     * Body: { "planCode": "SESSION_300" | "SESSION_500" | "SESSION_700" }
     */
    @PostMapping("/create-session")
    public ApiResponse<Object> createSession(@RequestBody Map<String, String> body) {
        String planCode = body.getOrDefault("planCode", "SESSION_500");
        ScanSession session = svc.createSimulatorSession(planCode);
        return ApiResponse.ok("Simulator session created", toSessionMap(session));
    }

    // ── Step 2: approve payment ───────────────────────────────────────────

    /**
     * Instantly activate the session — no payment gateway is called.
     */
    @PostMapping("/approve-payment/{sessionId}")
    public ApiResponse<Object> approvePayment(@PathVariable String sessionId) {
        ScanSession session = svc.approveSimulatorPayment(sessionId);
        return ApiResponse.ok("Payment approved — session is now ACTIVE", toSessionMap(session));
    }

    // ── Step 3: run scan ──────────────────────────────────────────────────

    /**
     * Run a full AI scan on the uploaded image.
     * Can be called multiple times while the session is ACTIVE.
     *
     * Body:
     * {
     *   "imageBase64":   "iVBORw0KGgo...",   // raw base-64, no data-URI prefix
     *   "imageContentType": "image/jpeg"       // or image/png / image/webp
     * }
     */
    @PostMapping("/scan/{sessionId}")
    public ApiResponse<Object> scan(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {

        String imageBase64 = body.get("imageBase64");
        String mimeType    = body.getOrDefault("imageContentType", "image/jpeg");

        ScanResult result = svc.analyzeSlip(sessionId, imageBase64, mimeType);
        return ApiResponse.ok("Scan complete", toResultMap(result));
    }

    // ── Queries ───────────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ApiResponse<Object> listSessions() {
        List<Map<String, Object>> list = svc.listSimulatorSessions().stream()
                .map(this::toSessionMap)
                .collect(Collectors.toList());
        return ApiResponse.ok("Simulator sessions (" + list.size() + ")", list);
    }

    @GetMapping("/results")
    public ApiResponse<Object> listResults() {
        List<Map<String, Object>> list = svc.listSimulatorResults().stream()
                .map(this::toResultMap)
                .collect(Collectors.toList());
        return ApiResponse.ok("Simulator scan results (" + list.size() + ")", list);
    }

    @GetMapping("/plans")
    public ApiResponse<Object> listPlans() {
        return ApiResponse.ok("Available scan session plans", svc.listPlans());
    }

    @GetMapping("/session/{sessionId}")
    public ApiResponse<Object> getSession(@PathVariable String sessionId) {
        return ApiResponse.ok("Simulator session", toSessionMap(svc.getSimulatorSession(sessionId)));
    }

    @PostMapping("/expire/{sessionId}")
    public ApiResponse<Object> expireSession(@PathVariable String sessionId) {
        ScanSession session = svc.expireSession(sessionId);
        return ApiResponse.ok("Session expired", toSessionMap(session));
    }

    // ── Private mappers ───────────────────────────────────────────────────

    private Map<String, Object> toSessionMap(ScanSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId",        s.getId());
        m.put("status",           s.getStatus().name());
        m.put("plan",             s.getPlan().name());
        m.put("planCode",         s.getPlan().getCode());
        m.put("amountCedis",      s.getPlan().getAmountCedis());
        m.put("durationMinutes",  s.getPlan().getDurationMinutes());
        m.put("scanCount",        s.getScanCount());
        m.put("secondsRemaining", s.secondsRemaining());
        m.put("activatedAt",      s.getActivatedAt());
        m.put("expiresAt",        s.getExpiresAt());
        m.put("createdAt",        s.getCreatedAt());
        m.put("simulatorSession", true);
        return m;
    }

    private Map<String, Object> toResultMap(ScanResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                r.getId());
        m.put("sessionId",         r.getPurchaseId());
        m.put("scanPlan",          r.getScanPlan().name());
        m.put("totalPicksDetected",r.getTotalPicksDetected());
        m.put("picksAnalyzed",     r.getPicksAnalyzed());
        m.put("coverageNote",      r.getCoverageNote());
        m.put("predictions", r.getPredictions() == null ? List.of() :
                r.getPredictions().stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("sectionIndex",   p.getSectionIndex());
                    pm.put("teamName",       p.getTeamName());
                    pm.put("matchLabel",     p.getMatchLabel());
                    pm.put("originalPick",   p.getOriginalPick());
                    pm.put("prediction",     p.getPrediction());
                    pm.put("accuracyPercent",p.getAccuracyPercent());
                    pm.put("confidence",     p.getConfidence());
                    pm.put("reason",         p.getReason());
                    return pm;
                }).collect(Collectors.toList()));
        if (r.getRawModelOutput() != null) {
            m.put("rawModelOutput", r.getRawModelOutput());
            m.put("rawOutputNote", "AI could not parse structured picks — raw output included");
        }
        m.put("createdAt",      r.getCreatedAt());
        m.put("simulatorResult", true);
        return m;
    }
}