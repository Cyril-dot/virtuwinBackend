package com.example.subscription.model;

import java.time.Instant;

/**
 * In-memory pending-intent ledger for subscription payments funded via
 * AkwaPay. Same shape and same accepted trade-off as
 * AkwaPayPendingScanIntent (used for scan purchases) — see that class's doc
 * for the full caveat about restarts. Kept as a separate class rather than
 * reused across both features so a change to one payment type's polling
 * behaviour can't accidentally affect the other.
 */
public class AkwaPayPendingSubscriptionIntent {

    private final String reference;      // sbsub_<32-hex>_<8-hex>, the map key
    private final String intentId;       // AkwaPay's pi_...
    private final String paymentId;      // AkwaPayPayment.id this intent is funding
    private final String email;
    private final Instant createdAt;
    private Instant lastCheckedAt;       // null == never polled

    public AkwaPayPendingSubscriptionIntent(String reference, String intentId, String paymentId,
                                            String email, Instant createdAt, Instant lastCheckedAt) {
        this.reference = reference;
        this.intentId = intentId;
        this.paymentId = paymentId;
        this.email = email;
        this.createdAt = createdAt;
        this.lastCheckedAt = lastCheckedAt;
    }

    public void markChecked(Instant when) { this.lastCheckedAt = when; }

    public String getReference() { return reference; }
    public String getIntentId() { return intentId; }
    public String getPaymentId() { return paymentId; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
}