package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.domain.model.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CreateOrderUseCase {
    /**
     * Validates, reserves inventory, authorizes payment, and confirms the order.
     * Phase 1: synchronous, no retries, no concurrency.
     */
    @Transactional
    Order createOrder(CreateOrderCommand command);

    // ── Command (immutable input object — no DTOs crossing into the domain) ──
    record CreateOrderCommand(
            String customerId,
            List<OrderItemCommand> items,
            String paymentMethod,
            String street,
            String city,
            String state,
            String postalCode,
            String country
    ) {
        public record OrderItemCommand(
                String productId, int quantity) {
        }
    }
}