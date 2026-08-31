package com.example.subscription.model;

public enum ScanSessionPlan {

    BASIC   ("SESSION_300", 300, 10),
    STANDARD("SESSION_500", 500, 20),
    PREMIUM ("SESSION_700", 700, 40);

    private final String code;
    private final int    amountCedis;
    private final int    durationMinutes;

    ScanSessionPlan(String code, int amountCedis, int durationMinutes) {
        this.code            = code;
        this.amountCedis     = amountCedis;
        this.durationMinutes = durationMinutes;
    }

    public String getCode()            { return code; }
    public int    getAmountCedis()     { return amountCedis; }
    public int    getDurationMinutes() { return durationMinutes; }

    public static ScanSessionPlan fromCode(String code) {
        if (code == null) throw new IllegalArgumentException("Unknown scan session plan: null");
        for (ScanSessionPlan p : values())
            if (p.code.equalsIgnoreCase(code) || p.name().equalsIgnoreCase(code)) return p;
        throw new IllegalArgumentException("Unknown scan session plan: " + code);
    }
}