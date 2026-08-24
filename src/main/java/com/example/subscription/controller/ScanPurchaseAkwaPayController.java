package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.ScanAkwaPayInitRequest;
import com.example.subscription.service.ScanPurchaseAkwaPayService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AkwaPay-funded alternative to {@link ScanPurchaseController}'s manual
 * screenshot flow.
 *
 *   1. POST /api/scan/payment/akwapay/init
 *        -> creates ScanPurchase (status=AWAITING_PAYMENT) + AkwaPay intent,
 *           returns AkwaPay's response (incl. next_action / checkout_url).
 *   2. Frontend follows next_action.type from the init response, or just
 *      uses checkout_url. See ScanPurchaseAkwaPayService's class-level
 *      "GATEWAY CHANGE" note: Flutterwave v4 mobile money uses
 *      next_action.type = "payment_instruction" (a push prompt), not
 *      "submit_otp" — there is no OTP endpoint on this controller any more.
 *   3. GET /api/scan/payment/akwapay/status/{intentId} - poll for UI purposes
 *      only; does NOT approve anything (same guarantee as
 *      AkwaPayController#status — a user hitting return_url proves nothing).
 *   4. Once AkwaPay confirms `succeeded` (webhook, or the reconciliation
 *      sweep in ScanPurchaseAkwaPayService within seconds), the matching
 *      ScanPurchase flips straight to APPROVED - no admin review step on
 *      this path. Poll the EXISTING endpoint
 *      GET /api/scan/payment/status/{purchaseId} (ScanPurchaseController) to
 *      see that happen, using the purchaseId returned from init.
 *
 * Webhook: POST /api/webhooks/akwapay/scan — separate path from the wallet
 * deposit webhook so the two flows never collide, using the SAME AkwaPay
 * webhook secret/signature scheme (one AkwaPay account, one whsec_, multiple
 * consuming endpoints is fine — AkwaPay just POSTs every event you
 * subscribed to at every registered endpoint URL).
 */
@RestController
public class ScanPurchaseAkwaPayController {

    private static final Logger log = LoggerFactory.getLogger(ScanPurchaseAkwaPayController.class);

    private final ScanPurchaseAkwaPayService akwaPayService;

    public ScanPurchaseAkwaPayController(ScanPurchaseAkwaPayService akwaPayService) {
        this.akwaPayService = akwaPayService;
    }

    @PostMapping("/api/scan/payment/akwapay/init")
    public ApiResponse<Object> init(@RequestBody ScanAkwaPayInitRequest req) {
        var result = akwaPayService.initPurchase(req.getEmail(), req.getPlan(), req.getPhone(), req.getNetwork());
        return ApiResponse.ok("AkwaPay scan purchase intent created", result);
    }

    @GetMapping("/api/scan/payment/akwapay/status/{intentId}")
    public ApiResponse<Object> status(@PathVariable String intentId) {
        var result = akwaPayService.status(intentId);
        return ApiResponse.ok("AkwaPay intent status", result);
    }

    /**
     * Returns 200 on every path that isn't a hard error, same policy as
     * AkwaPayController's webhook — a 400 here would put an event we'll never
     * handle back into AkwaPay's retry queue forever.
     */
    @PostMapping("/api/webhooks/akwapay/scan")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("scan webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        boolean ok = akwaPayService.handleWebhook(rawBody, signature);
        if (!ok) {
            return ResponseEntity.status(400).body("Invalid signature");
        }
        return ResponseEntity.ok("OK");
    }
}