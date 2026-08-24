package com.example.subscription.model;

import java.time.LocalDateTime;

/**
 * A subscription payment funded via AkwaPay, as an alternative to
 * {@link ManualPayment}'s screenshot-and-admin-review flow.
 *
 * Lifecycle:
 *   AWAITING_PAYMENT -> intent created at AkwaPay, waiting for the charge
 *                       to succeed (webhook or reconciliation sweep)
 *   APPROVED         -> AkwaPay confirmed the charge; account got its
 *                       password/plan assigned automatically, exactly like
 *                       a successful Paystack or approved-ManualPayment flow
 *   FAILED           -> AkwaPay reported the charge as failed/declined/
 *                       cancelled/expired; account untouched, user may
 *                       start a new attempt via /init
 *
 * There is no REJECTED state and no admin review step here — AkwaPay's own
 * status *is* the verification. passwordRevealed mirrors ManualPayment
 * exactly: the generated password is shown to the user exactly once, on the
 * first status check after approval.
 */
public class AkwaPayPayment {

    private final String id;
    private final String email;
    private final Plan plan;

    private final String reference;   // sbsub_<32-hex nonce>_<8-hex nonce>, the AkwaPay reference
    private String intentId;          // AkwaPay's pi_..., set once the intent is created

    private AkwaPayPaymentStatus status;
    private String failureReason;
    private boolean passwordRevealed;

    private final LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;

    public AkwaPayPayment(String id, String email, Plan plan, String reference) {
        this.id = id;
        this.email = email;
        this.plan = plan;
        this.reference = reference;
        this.status = AkwaPayPaymentStatus.AWAITING_PAYMENT;
        this.passwordRevealed = false;
        this.submittedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public Plan getPlan() { return plan; }

    public String getReference() { return reference; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }

    public AkwaPayPaymentStatus getStatus() { return status; }
    public void setStatus(AkwaPayPaymentStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public boolean isPasswordRevealed() { return passwordRevealed; }
    public void setPasswordRevealed(boolean passwordRevealed) { this.passwordRevealed = passwordRevealed; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}