package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.domain.model.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface CancelOrderUseCase {

    @Transactional
    Order cancelOrder(CancelOrderCommand command);

    record CancelOrderCommand(UUID orderId, String reason, UUID idempotencyKey) {
        public CancelOrderCommand {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation reason is required");
            }
        }
    }
}