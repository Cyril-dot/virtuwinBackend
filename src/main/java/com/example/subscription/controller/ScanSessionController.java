package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.ScanSessionInitRequest;
import com.example.subscription.service.ScanSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class ScanSessionController {

    private static final Logger log = LoggerFactory.getLogger(ScanSessionController.class);
    private final ScanSessionService service;

    public ScanSessionController(ScanSessionService service) { this.service = service; }

    @GetMapping("/api/scan/session/plans")
    public ApiResponse<Object> plans() {
        return ApiResponse.ok("Available scan session plans", service.listPlans());
    }

    @PostMapping("/api/scan/session/init")
    public ApiResponse<Object> init(@RequestBody ScanSessionInitRequest req) {
        var result = service.initSession(req.getEmail(), req.getPlan(), req.getPhone(), req.getNetwork());
        return ApiResponse.ok("Scan session payment intent created", result);
    }

    @GetMapping("/api/scan/session/status/{sessionId}")
    public ApiResponse<Object> status(@PathVariable String sessionId) {
        return ApiResponse.ok("Scan session status", service.sessionStatus(sessionId));
    }

    /** multipart fields: sessionId (text), email (text), image (file) */
    @PostMapping("/api/scan/session/analyze")
    public ApiResponse<Object> analyze(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("email")     String email,
            @RequestParam("image")     MultipartFile image) throws IOException {
        var result = service.analyze(sessionId, email, image.getBytes(),
                image.getContentType() != null ? image.getContentType() : "image/jpeg");
        return ApiResponse.ok("Scan complete", result);
    }

    @PostMapping("/api/webhooks/akwapay/scan-session")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature", required = false) String sig,
            HttpServletRequest req) {
        byte[] body;
        try { body = req.getInputStream().readAllBytes(); }
        catch (Exception e) { return ResponseEntity.status(400).body("Failed to read body"); }
        return service.handleWebhook(body, sig)
                ? ResponseEntity.ok("OK")
                : ResponseEntity.status(400).body("Invalid signature");
    }
}