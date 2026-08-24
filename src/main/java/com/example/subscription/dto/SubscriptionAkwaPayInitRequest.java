package com.example.subscription.dto;

/** Body for POST /api/payment/akwapay/init. */
public class SubscriptionAkwaPayInitRequest {

    private String email;
    private String plan;      // Plan code
    private String phone;     // Ghana MoMo number
    private String network;   // "MTN" | "TELECEL" | "AIRTELTIGO", optional

    public SubscriptionAkwaPayInitRequest() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
}