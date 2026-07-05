package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;

public interface PaymentStrategy {
    PaymentGateway.PaymentResult process(PaymentGateway.PaymentRequest request);
}