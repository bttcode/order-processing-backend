package com.bowt.backend.orderprocessing.infrastructure.persistence.mapper;

import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.OrderEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.OrderItemEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import com.bowt.backend.orderprocessing.domain.util.OrderIdGenerator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderMapper {

    public OrderEntity toNewEntity(Order order, List<ProductEntity> products) {
        OrderEntity entity = new OrderEntity();
        applyDomainFields(entity, order);
        entity.setItems(
                buildItemEntities(order.getItems(), entity, products));
        return entity;
    }

    public OrderEntity updateEntity(OrderEntity existing,
                                    Order order,
                                    List<ProductEntity> products) {
        // Never touch: pk, id (public UUID), created_at
        applyDomainFields(existing, order);

        // Replace items — orphanRemoval = true on the @OneToMany
        // will DELETE old rows and INSERT new ones automatically
        existing.getItems().clear();
        existing.getItems().addAll(
                buildItemEntities(order.getItems(), existing, products));

        return existing;
    }

    private void applyDomainFields(OrderEntity entity, Order order) {
        // Set public UUID only if not already set (INSERT path)
        if (entity.getId() == null) {
            entity.setId(order.getId() != null
                    ? order.getId()
                    : OrderIdGenerator.generate());
        }
        entity.setCustomerId(order.getCustomerId());
        entity.setStatus(order.getStatus());
        entity.setTotalAmount(order.getTotalAmount().getAmount());
        entity.setCurrency(order.getTotalAmount().getCurrency());
        entity.setPaymentMethod(order.getPaymentMethod());
        entity.setPaymentTransactionId(order.getPaymentTransactionId());
        entity.setUpdatedAt(order.getUpdatedAt());
        entity.setConfirmedAt(order.getConfirmedAt());
        entity.setEstimatedDeliveryDate(order.getEstimatedDeliveryDate());
        entity.setVersion(order.getVersion());
        // createdAt: set by @PrePersist on INSERT only — never overwrite
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(order.getCreatedAt());
        }
    }

    private List<OrderItemEntity> buildItemEntities(List<OrderItem> items,
                                                    OrderEntity parent,
                                                    List<ProductEntity> products) {
        return items.stream().map(item -> {
            ProductEntity pe = products.stream()
                    .filter(p -> p.getId().equals(item.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No ProductEntity for: " + item.getProductId()));

            OrderItemEntity e = new OrderItemEntity();
            e.setOrder(parent);
            e.setProduct(pe);
            e.setProductId(item.getProductId());
            e.setProductName(item.getProductName());
            e.setQuantity(item.getQuantity());
            e.setUnitPrice(item.getUnitPrice().getAmount());
            e.setTotalPrice(item.getTotalPrice().getAmount());
            return e;
        }).toList();
    }

    public Order toDomain(OrderEntity entity) {
        Order order = new Order();
        order.setId(entity.getId());
        order.setCustomerId(entity.getCustomerId());
        order.setStatus(entity.getStatus());
        order.setPaymentMethod(entity.getPaymentMethod());
        order.setPaymentTransactionId(entity.getPaymentTransactionId());
        order.setCreatedAt(entity.getCreatedAt());
        order.setUpdatedAt(entity.getUpdatedAt());
        order.setConfirmedAt(entity.getConfirmedAt());
        order.setEstimatedDeliveryDate(entity.getEstimatedDeliveryDate());
        order.setVersion(entity.getVersion());

        if (entity.getItems() != null) {
            entity.getItems().forEach(ie -> order.addItem(new OrderItem(
                    ie.getProductId(),
                    ie.getProductName(),
                    ie.getQuantity(),
                    Money.of(ie.getUnitPrice())
            )));
        }
        return order;
    }
}
