package com.mercala.payment;

public interface PaymentProvider {
    PaymentResponse initializePayment(PaymentRequest request);
    PaymentResponse verifyPayment(String transactionReference);
    RefundResponse refundPayment(RefundRequest request);
}
