package com.bowt.backend.orderprocessing.domain;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentRequest;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentResult;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentService}.
 *
 * <p>NOTE: {@code @Retryable} / {@code RetryAspect} is an AOP proxy concern
 * that only applies when the bean is retrieved from the Spring context.
 * These tests instantiate {@code PaymentService} directly — they verify the
 * core logic (delegate to gateway, throw on failure) without AOP.
 * AOP retry integration is covered in the integration test slice.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    PaymentGateway paymentGateway;

    PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentGateway);
    }

    @Test
    void authorize_gatewaySucceeds_returnsResult() {
        Order order = buildOrder();
        PaymentResult gatewayResult = new PaymentResult(true, "TXN-123", null);
        when(paymentGateway.authorize(any(PaymentRequest.class))).thenReturn(gatewayResult);

        PaymentResult result = paymentService.authorize(order);

        assertThat(result.success()).isTrue();
        assertThat(result.transactionId()).isEqualTo("TXN-123");
    }

    @Test
    void authorize_gatewayReturnsFalse_throwsPaymentFailed() {
        Order order = buildOrder();
        when(paymentGateway.authorize(any()))
                .thenReturn(new PaymentResult(false, null, "Card declined"));

        assertThatThrownBy(() -> paymentService.authorize(order))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Card declined");
    }

    @Test
    void authorize_gatewayThrows_wrapsInPaymentFailed() {
        Order order = buildOrder();
        when(paymentGateway.authorize(any()))
                .thenThrow(new RuntimeException("Network error"));

        assertThatThrownBy(() -> paymentService.authorize(order))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Network error");
    }

    @Test
    void refund_delegatesToGateway() {
        paymentService.refund("TXN-456", Money.of("99.99"));
        verify(paymentGateway, times(1)).refund("TXN-456", Money.of("99.99"));
    }

    @Test
    void capture_delegatesToGateway() {
        paymentService.capture("TXN-789");
        verify(paymentGateway, times(1)).capture("TXN-789");
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private Order buildOrder() {
        Order order = new Order("CUST-001");
        order.setPaymentMethod("CREDIT_CARD");
        return order;
    }
}