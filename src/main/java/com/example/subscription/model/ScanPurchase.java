package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * A single purchase of an AI betting-slip scan.
 *
 * Two payment methods now feed this model:
 *
 *   MANUAL  - the original flow. User submits a screenshot + reference for
 *             an admin to review by hand. Starts life at status=PENDING.
 *
 *   AKWAPAY - user pays by mobile money through AkwaPay. Starts life at
 *             status=AWAITING_PAYMENT with no screenshot fields populated;
 *             moves straight to APPROVED once AkwaPay confirms the charge
 *             (webhook or reconciliation sweep) — no admin step. See
 *             ScanPurchaseAkwaPayService.
 *
 * The manual-only fields (accountName, accountNumber, networkOrBank,
 * reference, screenshotUrl, reviewedByAdmin, rejectionReason) are null for
 * AKWAPAY purchases. akwapayReference / akwapayIntentId are null for MANUAL
 * purchases. Nothing about the existing MANUAL fields changed shape or
 * meaning.
 */
public class ScanPurchase {

    private final String id;
    private final String email;
    private final ScanPlan scanPlan;
    private final PaymentMethod paymentMethod;

    // ── Manual-payment fields (null when paymentMethod == AKWAPAY) ──────────
    private final String accountName;
    private final String accountNumber;
    private final String networkOrBank;
    private final String reference;
    private final String screenshotUrl;

    // ── AkwaPay fields (null when paymentMethod == MANUAL) ───────────────────
    private final String akwapayReference;   // sbscn_<32-hex nonce>_<8-hex nonce>, see ScanPurchaseAkwaPayService
    private String akwapayIntentId;          // pi_..., set once AkwaPay creates the intent

    private ScanPurchaseStatus status;
    private final LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private String reviewedByAdmin;
    private String rejectionReason;
    private LocalDateTime usedAt;

    /** Full constructor for the MANUAL flow (unchanged from the original shape). */
    public ScanPurchase(String id, String email, ScanPlan scanPlan, String accountName, String accountNumber,
                        String networkOrBank, String reference, String screenshotUrl) {
        this.id = id;
        this.email = email;
        this.scanPlan = scanPlan;
        this.paymentMethod = PaymentMethod.MANUAL;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.networkOrBank = networkOrBank;
        this.reference = reference;
        this.screenshotUrl = screenshotUrl;
        this.akwapayReference = null;
        this.akwapayIntentId = null;
        this.status = ScanPurchaseStatus.PENDING;
        this.submittedAt = LocalDateTime.now();
    }

    /**
     * Constructor for the AKWAPAY flow. Starts at AWAITING_PAYMENT with no
     * screenshot/manual fields — those stay null for the life of this object.
     */
    public ScanPurchase(String id, String email, ScanPlan scanPlan, String akwapayReference) {
        this.id = id;
        this.email = email;
        this.scanPlan = scanPlan;
        this.paymentMethod = PaymentMethod.AKWAPAY;
        this.accountName = null;
        this.accountNumber = null;
        this.networkOrBank = null;
        this.reference = null;
        this.screenshotUrl = null;
        this.akwapayReference = akwapayReference;
        this.akwapayIntentId = null;
        this.status = ScanPurchaseStatus.AWAITING_PAYMENT;
        this.submittedAt = LocalDateTime.now();
    }

    public enum PaymentMethod { MANUAL, AKWAPAY }

    // ── Getters ───────────────────────────────────────────────────────────
    public String getId() { return id; }
    public String getEmail() { return email; }
    public ScanPlan getScanPlan() { return scanPlan; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }

    public String getAccountName() { return accountName; }
    public String getAccountNumber() { return accountNumber; }
    public String getNetworkOrBank() { return networkOrBank; }
    public String getReference() { return reference; }
    public String getScreenshotUrl() { return screenshotUrl; }

    public String getAkwapayReference() { return akwapayReference; }
    public String getAkwapayIntentId() { return akwapayIntentId; }
    public void setAkwapayIntentId(String akwapayIntentId) { this.akwapayIntentId = akwapayIntentId; }

    public ScanPurchaseStatus getStatus() { return status; }
    public void setStatus(ScanPurchaseStatus status) { this.status = status; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewedByAdmin() { return reviewedByAdmin; }
    public void setReviewedByAdmin(String reviewedByAdmin) { this.reviewedByAdmin = reviewedByAdmin; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
}