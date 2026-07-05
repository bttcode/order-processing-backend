package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import org.springframework.stereotype.Component;

/** Crypto has no chargeback path — refund() on this method should be treated as best-effort by callers. */
@Component
public class CryptoPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway paymentGateway;

    public CryptoPaymentStrategy(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @Override
    public PaymentGateway.PaymentResult process(PaymentGateway.PaymentRequest request) {
        return paymentGateway.authorize(request);
    }
}