package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.SubscriptionAkwaPayInitRequest;
import com.example.subscription.dto.SubscriptionAkwaPayOtpRequest;
import com.example.subscription.model.AkwaPayPayment;
import com.example.subscription.service.SubscriptionAkwaPayService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AkwaPay-funded replacement for {@link ManualPaymentController}'s manual
 * screenshot flow. Same user-facing shape as ManualPaymentController's
 * /status/{id} endpoint (including the one-time password reveal), plus the
 * AkwaPay-specific init/otp/webhook plumbing.
 *
 * Flow:
 *   1. POST /api/payment/akwapay/init
 *        -> creates AkwaPayPayment (status=AWAITING_PAYMENT) + AkwaPay
 *           intent, returns { paymentId, akwapay: {...} }.
 *   2. Frontend follows next_action.type same as any AkwaPay flow:
 *        await_prompt | redirect | submit_otp | none, or just uses
 *        checkout_url, which handles all four.
 *      - if submit_otp: POST /api/payment/akwapay/otp
 *   3. GET /api/payment/akwapay/intent-status/{intentId} - poll AkwaPay's
 *      own intent status for UI purposes only; does NOT approve anything.
 *   4. GET /api/payment/akwapay/status/{paymentId} - poll THIS for the
 *      actual outcome. Once AkwaPay confirms `succeeded` (webhook, or the
 *      reconciliation sweep, usually within seconds), the account gets its
 *      subscription/password assigned automatically and this endpoint
 *      reveals the password exactly once - same contract as
 *      ManualPaymentController#status.
 *
 * Webhook: POST /api/webhooks/akwapay/subscription — separate path from
 * both the wallet-deposit and scan-purchase webhooks so all three flows
 * stay independent, using the SAME AkwaPay webhook secret/signature scheme.
 * Registered as its own top-level mapping below (not nested under
 * /api/payment/akwapay) to match the /api/webhooks/** convention used by
 * the other two flows.
 */
@RestController
public class SubscriptionAkwaPayController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAkwaPayController.class);

    private final SubscriptionAkwaPayService akwaPayService;

    public SubscriptionAkwaPayController(SubscriptionAkwaPayService akwaPayService) {
        this.akwaPayService = akwaPayService;
    }

    @PostMapping("/api/payment/akwapay/init")
    public ApiResponse<Object> init(@RequestBody SubscriptionAkwaPayInitRequest req) {
        var result = akwaPayService.initPayment(req.getEmail(), req.getPlan(), req.getPhone(), req.getNetwork());
        return ApiResponse.ok("AkwaPay subscription payment intent created", result);
    }

    /** AkwaPay's own intent status, for UI purposes only - does not approve anything. */
    @GetMapping("/api/payment/akwapay/intent-status/{intentId}")
    public ApiResponse<Object> intentStatus(@PathVariable String intentId) {
        var result = akwaPayService.akwapayIntentStatus(intentId);
        return ApiResponse.ok("AkwaPay intent status", result);
    }

    @PostMapping("/api/payment/akwapay/otp")
    public ApiResponse<Object> submitOtp(@RequestBody SubscriptionAkwaPayOtpRequest req) {
        var result = akwaPayService.submitOtp(req.getIntentId(), req.getClientSecret(), req.getOtp());
        return ApiResponse.ok("OTP submitted", result);
    }

    /**
     * Poll this with the paymentId returned from /init. Once AkwaPay
     * confirms the charge, the response includes the generated login
     * password - shown exactly once, on the first status check after
     * approval. Mirrors ManualPaymentController#status exactly.
     */
    @GetMapping("/api/payment/akwapay/status/{paymentId}")
    public ApiResponse<Object> status(@PathVariable String paymentId) {
        SubscriptionAkwaPayService.StatusResult result = akwaPayService.checkStatus(paymentId);
        AkwaPayPayment payment = result.payment;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", payment.getId());
        data.put("status", payment.getStatus());
        data.put("plan", payment.getPlan().name());
        data.put("submittedAt", payment.getSubmittedAt());
        data.put("reviewedAt", payment.getReviewedAt());

        if (payment.getStatus().name().equals("FAILED")) {
            data.put("failureReason", payment.getFailureReason());
        }

        if (result.password != null) {
            data.put("username", payment.getEmail());
            data.put("password", result.password);
            data.put("message", "Save this password now - it will not be shown again. Use it to log in.");
        } else if (payment.getStatus().name().equals("APPROVED")) {
            data.put("message", "Already approved - use the password you saved earlier to log in.");
        }

        return ApiResponse.ok("AkwaPay subscription payment status", data);
    }

    /**
     * Returns 200 on every path that isn't a hard error, same policy as the
     * wallet and scan-purchase webhooks - a 400 here would put an event
     * we'll never handle back into AkwaPay's retry queue forever.
     */
    @PostMapping("/api/webhooks/akwapay/subscription")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("subscription webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        boolean ok = akwaPayService.handleWebhook(rawBody, signature);
        if (!ok) {
            return ResponseEntity.status(400).body("Invalid signature");
        }
        return ResponseEntity.ok("OK");
    }
}