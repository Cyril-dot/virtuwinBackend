package com.example.subscription.model;

/**
 * The three subscription packages offered on the platform.
 * Amount is in Ghana Cedis (GHS) and Paystack expects the amount in the
 * smallest currency unit (pesewas) i.e. amount * 100.
 */
public enum Plan {

    TWO_HOUR("2HR", 1, 2),//300
    THREE_HOUR("3HR", 1, 3), // 400
    FIVE_HOUR("5HR", 1, 5); // 700

    private final String code;
    private final int amountCedis;
    private final int hours;

    Plan(String code, int amountCedis, int hours) {
        this.code = code;
        this.amountCedis = amountCedis;
        this.hours = hours;
    }

    public String getCode() {
        return code;
    }

    public int getAmountCedis() {
        return amountCedis;
    }

    public int getHours() {
        return hours;
    }

    public long getAmountInPesewas() {
        return amountCedis * 100L;
    }

    public static Plan fromCode(String code) {
        for (Plan p : values()) {
            if (p.code.equalsIgnoreCase(code) || p.name().equalsIgnoreCase(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown plan: " + code);
    }
}
