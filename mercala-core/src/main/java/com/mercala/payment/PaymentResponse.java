package com.mercala.payment;

public record PaymentResponse(
    boolean success,
    String status, // e.g. "PENDING", "SUCCESSFUL", "FAILED"
    String providerReference,
    String checkoutUrl,
    String rawResponse,
    String errorMessage
) {
    public static PaymentResponse pending(String providerReference, String checkoutUrl) {
        return new PaymentResponse(true, "PENDING", providerReference, checkoutUrl, null, null);
    }
    
    public static PaymentResponse successful(String providerReference, String rawResponse) {
        return new PaymentResponse(true, "SUCCESSFUL", providerReference, null, rawResponse, null);
    }
    
    public static PaymentResponse failed(String errorMessage) {
        return new PaymentResponse(false, "FAILED", null, null, null, errorMessage);
    }
}
