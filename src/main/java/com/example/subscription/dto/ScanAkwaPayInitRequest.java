package com.example.subscription.dto;

/**
 * Body for POST /api/scan/payment/akwapay/init.
 *
 * network is optional - if omitted, it's derived from phone's dialling
 * prefix the same way AkwaPayController.resolveNetwork does; if it can't be
 * derived, the request fails with 400 asking the caller to supply it
 * explicitly.
 */
public class ScanAkwaPayInitRequest {

    private String email;
    private String plan;           // ScanPlan code, e.g. "SCAN_300"
    private String phone;          // Ghana MoMo number, any common format
    private String network;        // "MTN" | "TELECEL" | "AIRTELTIGO", optional

    public ScanAkwaPayInitRequest() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
}