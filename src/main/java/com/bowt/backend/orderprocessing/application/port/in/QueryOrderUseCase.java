package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.domain.model.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface QueryOrderUseCase {
    @Transactional(readOnly = true)
    Optional<Order> findById(UUID orderId);
}
