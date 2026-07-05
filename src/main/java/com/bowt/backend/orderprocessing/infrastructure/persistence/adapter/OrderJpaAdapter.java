package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.*;
import com.bowt.backend.orderprocessing.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class OrderJpaAdapter implements OrderRepository {

    private final JpaOrderRepository jpaOrderRepo;
    private final JpaOrderItemRepository jpaOrderItemRepo;
    private final JpaProductRepository jpaProductRepo;
    private final OrderMapper mapper;

    @Override
    public Optional<Order> findById(UUID publicId) {
        return jpaOrderRepo.findByPublicId(publicId).map(mapper::toDomain);
    }

    @Override
    public List<Order> findByCustomerIdAndFilters(String customerId, OrderStatus status,
                                                  Instant from, Instant to, Pageable pageable) {
        return jpaOrderRepo.findAll(
                        resolveOrderFilters(customerId, status, from, to), resolvePageable(pageable))
                .getContent().stream().map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public long countByCustomerIdAndFilters(String customerId, OrderStatus status, Instant from, Instant to) {
        return jpaOrderRepo.count(resolveOrderFilters(customerId, status, from, to));
    }

    @Override
    public Order save(Order order) {
        Map<String, ProductEntity> productsByBusinessId = resolveProductEntities(order);

        Optional<OrderEntity> existing = order.getId() != null
                ? jpaOrderRepo.findEntityByPublicId(order.getId()) // no JOIN FETCH
                : Optional.empty();

        OrderEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            jpaOrderItemRepo.deleteByOrderPk(entity.getPk()); // avoid fetch when clear items
            mapper.updateEntity(entity, order, productsByBusinessId);
        } else {
            entity = mapper.toNewEntity(order, productsByBusinessId);
        }

        OrderEntity saved = jpaOrderRepo.save(entity);
        return mapper.toDomain(saved);
    }

    /**
     * Previously: one findByBusinessId() SELECT per order item (N+1). At 10k
     * orders/sec * ~2 items avg = 20k extra round-trips/sec — enough on its own to blow
     * the p99 latency SLA and invalidate every Phase 3 load-test result. Now: one batched
     * `WHERE id IN (...)` query per order, regardless of item count.
     */
    private Map<String, ProductEntity> resolveProductEntities(Order order) {
        List<String> productIds = order.getItems().stream()
                .map(OrderItem::getProductId)
                .distinct()
                .collect(Collectors.toList());

        List<ProductEntity> found = jpaProductRepo.findAllByBusinessIds(productIds);

        if (found.size() != productIds.size()) {
            Set<String> foundIds = found.stream().map(ProductEntity::getId).collect(Collectors.toSet());
            String missing = productIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .findFirst()
                    .orElse("unknown");
            throw new IllegalStateException("ProductEntity not found for business id: " + missing);
        }

        return found.stream().collect(Collectors.toMap(ProductEntity::getId, p -> p));
    }

    private org.springframework.data.domain.Pageable resolvePageable(Pageable pageable) {
        if (pageable == null) {
            // Fallback to Spring's unpaged or a default if preferred
            return org.springframework.data.domain.Pageable.unpaged();
        }

        // 1. Determine the direction
        Sort.Direction direction = pageable.descending()
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        // 2. Create the Sort object (handles null/empty gracefully if needed)
        Sort sort = (pageable.sortBy() != null && !pageable.sortBy().isBlank())
                ? Sort.by(direction, pageable.sortBy())
                : Sort.unsorted();

        // 3. Map to Spring Data's PageRequest
        return PageRequest.of(pageable.page(), pageable.size(), sort);
    }

    private Specification<OrderEntity> resolveOrderFilters(String customerId, OrderStatus status,
                                                           Instant from, Instant to) {
        Specification<OrderEntity> spec = Specification.where((root, query, cb) ->
                cb.equal(root.get("customerId"), customerId)
        );

        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }

        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        return spec;
    }
}