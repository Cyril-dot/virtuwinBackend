package com.example.subscription.model;

import java.time.Instant;

public class AkwaPayPendingScanSessionIntent {

    private final String  reference;   // scsn-<32-hex>-<8-hex>
    private final String  intentId;    // AkwaPay's pi_...
    private final String  sessionId;   // ScanSession.id
    private final String  email;
    private final Instant createdAt;
    private       Instant lastCheckedAt;

    public AkwaPayPendingScanSessionIntent(String reference, String intentId, String sessionId,
                                           String email, Instant createdAt, Instant lastCheckedAt) {
        this.reference      = reference;
        this.intentId       = intentId;
        this.sessionId      = sessionId;
        this.email          = email;
        this.createdAt      = createdAt;
        this.lastCheckedAt  = lastCheckedAt;
    }

    public void    markChecked(Instant when) { this.lastCheckedAt = when; }
    public String  getReference()     { return reference; }
    public String  getIntentId()      { return intentId; }
    public String  getSessionId()     { return sessionId; }
    public String  getEmail()         { return email; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
}