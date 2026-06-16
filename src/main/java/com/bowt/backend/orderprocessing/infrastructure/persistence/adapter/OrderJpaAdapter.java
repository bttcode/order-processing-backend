package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaOrderRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.OrderEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OrderJpaAdapter implements OrderRepository {

    private final JpaOrderRepository jpaRepo;
    private final JpaProductRepository jpaProductRepo;
    private final OrderMapper mapper;

    @Override
    public Order save(Order order) {
        List<ProductEntity> products = resolveProductEntities(order);

        OrderEntity entity;
        if (order.getId() == null) {
            entity = mapper.toNewEntity(order, products);
        } else {
            entity = jpaRepo.findByPublicId(order.getId())
                    .map(existing -> mapper.updateEntity(existing, order, products))
                    .orElseGet(() -> mapper.toNewEntity(order, products));
        }
        OrderEntity saved = jpaRepo.save(entity);

        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Order> findById(UUID publicId) {
        return jpaRepo.findByPublicId(publicId).map(mapper::toDomain);
    }

    private List<ProductEntity> resolveProductEntities(Order order) {
        return order.getItems().stream()
                .map(item -> jpaProductRepo.findByBusinessId(item.getProductId())
                        .orElseThrow(() -> new IllegalStateException(
                                "ProductEntity not found for: " + item.getProductId())))
                .toList();
    }
}