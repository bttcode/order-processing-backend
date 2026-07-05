package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import org.springframework.stereotype.Component;

@Component
public class PayPalPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway paymentGateway;

    public PayPalPaymentStrategy(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @Override
    public PaymentGateway.PaymentResult process(PaymentGateway.PaymentRequest request) {
        return paymentGateway.authorize(request);
    }
}