package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryOrderUseCase {

    @Transactional(readOnly = true)
    Optional<Order> findById(UUID orderId);

    @Transactional(readOnly = true)
    PagedResult findByCustomerIdAndFilters(String customerId, OrderStatus status, Instant from,
                                           Instant to, OrderRepository.Pageable pageable);

    record PagedResult(List<Order> content, int page, int size, long totalElements, int totalPages) {
        public static PagedResult of(List<Order> content, OrderRepository.Pageable pageable, long totalElements) {
            int totalPages = pageable.size() == 0 ? 0 : (int) Math.ceil((double) totalElements / pageable.size());
            return new PagedResult(content, pageable.page(), pageable.size(), totalElements, totalPages);
        }
    }
}