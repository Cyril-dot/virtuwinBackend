package com.example.subscription.model;

import java.time.LocalDateTime;

public class ScanSession {

    public enum Status { AWAITING_PAYMENT, ACTIVE, EXPIRED, FAILED }

    private final String          id;
    private final String          email;
    private final ScanSessionPlan plan;
    private final String          akwapayReference;
    private       String          akwapayIntentId;

    private Status        status;
    private final LocalDateTime createdAt;
    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;
    private int           scanCount;

    public ScanSession(String id, String email, ScanSessionPlan plan, String akwapayReference) {
        this.id               = id;
        this.email            = email;
        this.plan             = plan;
        this.akwapayReference = akwapayReference;
        this.status           = Status.AWAITING_PAYMENT;
        this.createdAt        = LocalDateTime.now();
        this.scanCount        = 0;
    }

    /** True only when ACTIVE and time is still remaining. */
    public boolean canScan() {
        return status == Status.ACTIVE
            && expiresAt != null
            && LocalDateTime.now().isBefore(expiresAt);
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public long secondsRemaining() {
        if (expiresAt == null) return 0;
        long s = java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
        return Math.max(s, 0);
    }

    /** Called once by ScanSessionService.activate() — starts the countdown. */
    public void activate() {
        this.status      = Status.ACTIVE;
        this.activatedAt = LocalDateTime.now();
        this.expiresAt   = activatedAt.plusMinutes(plan.getDurationMinutes());
    }

    public void markExpired() { this.status = Status.EXPIRED; }
    public void markFailed()  { this.status = Status.FAILED; }
    public void incrementScanCount() { this.scanCount++; }

    public String          getId()               { return id; }
    public String          getEmail()            { return email; }
    public ScanSessionPlan getPlan()             { return plan; }
    public String          getAkwapayReference() { return akwapayReference; }
    public String          getAkwapayIntentId()  { return akwapayIntentId; }
    public void            setAkwapayIntentId(String v) { this.akwapayIntentId = v; }
    public Status          getStatus()           { return status; }
    public LocalDateTime   getCreatedAt()        { return createdAt; }
    public LocalDateTime   getActivatedAt()      { return activatedAt; }
    public LocalDateTime   getExpiresAt()        { return expiresAt; }
    public int             getScanCount()        { return scanCount; }
}