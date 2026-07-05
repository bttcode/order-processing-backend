package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import org.springframework.stereotype.Component;

/**
 * AR-2 Pattern 1 (Strategy). Distinct from PaymentGateway (Adapter, Pattern 5):
 * Strategy resolves which *method* to use (card/PayPal/crypto); Adapter resolves which
 * *provider* processes it (Stripe/Braintree/Mock). A CREDIT_CARD payment still goes
 * through whichever PaymentGateway is active — this class exists so method-specific
 * pre-processing (e.g. card BIN routing, 3DS hints) has a place to live without
 * touching the gateway abstraction.
 */
@Component
public class CreditCardPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway paymentGateway;

    public CreditCardPaymentStrategy(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @Override
    public PaymentGateway.PaymentResult process(PaymentGateway.PaymentRequest request) {
        return paymentGateway.authorize(request);
    }
}