package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.domain.model.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface QueryOrderUseCase {
    @Transactional(readOnly = true)
    Optional<Order> findById(UUID orderId);
    // TODO Phase 1 basic only; paginated list query added in Phase 2
}
