package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;

public interface PaymentGateway {

    PaymentResult authorize(PaymentRequest request);

    void capture(String transactionId);

    void refund(String transactionId, Money amount);

    record PaymentRequest(String orderId, Money amount, PaymentMethod paymentMethod) {
    }

    record PaymentResult(boolean success, String transactionId, String failureReason) {
        public static PaymentResult success(String transactionId) {
            return new PaymentResult(true, transactionId, null);
        }

        public static PaymentResult failure(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}