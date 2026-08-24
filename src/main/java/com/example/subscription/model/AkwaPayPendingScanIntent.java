package com.example.subscription.model;

import java.time.Instant;

/**
 * In-memory twin of the pending-intent ledger described in AkwaPayController's
 * class-level doc comment, scoped to scan purchases.
 *
 * IMPORTANT CAVEAT vs. the wallet-deposit version this is modelled on: that
 * version lives in a Postgres table specifically because an in-memory ledger
 * is unsafe across restarts — AkwaPay has no "list my intents" endpoint, so an
 * intent id lost to a restart can never be recovered, and the money is gone
 * from this service's point of view even though AkwaPay collected it.
 *
 * That risk is unchanged here. This class is in-memory only because that's
 * what was asked for and it matches ScanPurchaseService's existing style
 * (InMemoryScanPurchaseRepository). If this ships to a PaaS that restarts
 * pods (the AkwaPayController doc cites three restarts in ninety seconds on
 * 2026-08-12), any AWAITING_PAYMENT scan purchase whose row is lost on
 * restart will need to be reconciled by hand — check AkwaPay's dashboard for
 * the reference (prefix "sbscn_") and call the admin approve endpoint, or
 * re-run reconcileOne manually once the row is gone. Move this to a JPA
 * repository the same way AkwaPayPendingIntent is if that risk becomes a
 * problem.
 */
public class AkwaPayPendingScanIntent {

    private final String reference;      // sbscn_<32-hex>_<8-hex>, the map key
    private final String intentId;       // AkwaPay's pi_...
    private final String purchaseId;     // ScanPurchase.id this intent is funding
    private final String email;
    private final Instant createdAt;
    private Instant lastCheckedAt;       // null == never polled

    public AkwaPayPendingScanIntent(String reference, String intentId, String purchaseId,
                                    String email, Instant createdAt, Instant lastCheckedAt) {
        this.reference = reference;
        this.intentId = intentId;
        this.purchaseId = purchaseId;
        this.email = email;
        this.createdAt = createdAt;
        this.lastCheckedAt = lastCheckedAt;
    }

    public void markChecked(Instant when) { this.lastCheckedAt = when; }

    public String getReference() { return reference; }
    public String getIntentId() { return intentId; }
    public String getPurchaseId() { return purchaseId; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
}