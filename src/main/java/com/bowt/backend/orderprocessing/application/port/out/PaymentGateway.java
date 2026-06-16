package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.model.Money;

public interface PaymentGateway {

    record PaymentRequest(String orderId, Money amount, String paymentMethod) {}
    record PaymentResult(boolean success, String transactionId, String failureReason) {}

    PaymentResult authorize(PaymentRequest request);
    void capture(String transactionId);
    void refund(String transactionId, Money amount);
}
