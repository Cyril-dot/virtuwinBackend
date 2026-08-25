package com.example.subscription.model;

/**
 * The three AI betting-slip scan tiers. These are completely separate from
 * the time-based access {@link Plan} (2HR/3HR/5HR) — a user can hold one of
 * each independently. Payment for these is manual only (mobile money / bank
 * transfer proof, reviewed by an admin), same flow as {@code ManualPayment}.
 *
 * Each approved purchase grants exactly ONE scan of ONE betting-slip image.
 * maxPicks caps how many of the picks/games found on that slip the AI will
 * actually analyze and return a prediction for:
 *   - BASIC:    up to 2 picks analyzed
 *   - STANDARD: up to 5 picks analyzed
 *   - PREMIUM:  every pick on the slip analyzed (full coverage)
 *
 * If the slip has fewer picks than the cap, all of them are covered.
 */
public enum ScanPlan {

    BASIC("SCAN_300", 1, 3),// 400
    STANDARD("SCAN_500", 1, 5), // 700
    PREMIUM("SCAN_1000", 1, -1); //1000

    private final String code;
    private final int amountCedis;
    private final int maxPicks;

    ScanPlan(String code, int amountCedis, int maxPicks) {
        this.code = code;
        this.amountCedis = amountCedis;
        this.maxPicks = maxPicks;
    }

    public String getCode() {
        return code;
    }

    public int getAmountCedis() {
        return amountCedis;
    }

    /** -1 means unlimited / full coverage of every pick found on the slip. */
    public int getMaxPicks() {
        return maxPicks;
    }

    public boolean isFullCoverage() {
        return maxPicks < 0;
    }

    public static ScanPlan fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Unknown scan plan: null");
        }
        for (ScanPlan p : values()) {
            if (p.code.equalsIgnoreCase(code) || p.name().equalsIgnoreCase(code)) {
                return p;
            }
        }
        // Also accept the bare amount, e.g. "300", "500", "1000"
        for (ScanPlan p : values()) {
            if (String.valueOf(p.amountCedis).equals(code.trim())) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown scan plan: " + code);
    }
}
