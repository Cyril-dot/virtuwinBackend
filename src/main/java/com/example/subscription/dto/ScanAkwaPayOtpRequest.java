package com.example.subscription.dto;

/** Body for POST /api/scan/payment/akwapay/otp. All three fields required. */
public class ScanAkwaPayOtpRequest {

    private String intentId;
    private String clientSecret;
    private String otp;

    public ScanAkwaPayOtpRequest() {}

    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}