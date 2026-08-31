package com.example.subscription.dto;

public class ScanSessionInitRequest {
    private String email;
    private String plan;     // SESSION_300 | SESSION_500 | SESSION_700
    private String phone;
    private String network;  // MTN | TELECEL | AIRTELTIGO (optional)

    public ScanSessionInitRequest() {}
    public String getEmail()   { return email; }
    public void setEmail(String v)   { this.email = v; }
    public String getPlan()    { return plan; }
    public void setPlan(String v)    { this.plan = v; }
    public String getPhone()   { return phone; }
    public void setPhone(String v)   { this.phone = v; }
    public String getNetwork() { return network; }
    public void setNetwork(String v) { this.network = v; }
}