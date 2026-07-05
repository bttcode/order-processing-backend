package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentStrategyFactory {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    public PaymentStrategyFactory(CreditCardPaymentStrategy creditCard,
                                  PayPalPaymentStrategy payPal,
                                  CryptoPaymentStrategy crypto) {
        this.strategies = Map.of(
                PaymentMethod.CREDIT_CARD, creditCard,
                PaymentMethod.PAYPAL, payPal,
                PaymentMethod.CRYPTO, crypto);
    }

    public PaymentStrategy resolve(PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw new IllegalArgumentException("No PaymentStrategy registered for " + method);
        }
        return strategy;
    }
}