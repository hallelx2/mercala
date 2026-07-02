package com.mercala.payment;

public record RefundResponse(
    boolean success,
    String status, // e.g. "REFUNDED", "FAILED"
    String refundReference,
    String errorMessage
) {
    public static RefundResponse successful(String refundReference) {
        return new RefundResponse(true, "REFUNDED", refundReference, null);
    }
    
    public static RefundResponse failed(String errorMessage) {
        return new RefundResponse(false, "FAILED", null, errorMessage);
    }
}
