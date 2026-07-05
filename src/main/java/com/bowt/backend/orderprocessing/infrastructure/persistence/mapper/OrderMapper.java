package com.bowt.backend.orderprocessing.infrastructure.persistence.mapper;

import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.ShippingAddress;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.OrderEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.OrderItemEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ShippingAddressEmbeddable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OrderMapper {

    public Order toDomain(OrderEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(this::itemToDomain)
                .collect(Collectors.toList());

        return Order.reconstruct(
                entity.getId(),
                entity.getCustomerId(),
                entity.getStatus(),
                items,
                entity.getPaymentMethod(),
                entity.getPaymentTransactionId(),
                toDomain(entity.getShippingAddress()),
                entity.getEstimatedDeliveryDate(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getConfirmedAt());
    }

    private OrderItem itemToDomain(OrderItemEntity e) {
        return new OrderItem(
                e.getProductId(), e.getProductName(), e.getQuantity(), Money.of(e.getUnitPrice()));
    }

    /**
     * New order path. `productsByBusinessId` must contain every product referenced by
     * order.getItems() — callers batch-fetch via JpaProductRepository.findAllByBusinessIds
     * rather than looking each one up individually here.
     */
    public OrderEntity toNewEntity(Order order, Map<String, ProductEntity> productsByBusinessId) {
        OrderEntity entity = new OrderEntity();
        entity.setId(order.getId());
        entity.setCustomerId(order.getCustomerId());
        entity.setStatus(order.getStatus());
        entity.setTotalAmount(order.getTotalAmount().getAmount());
        entity.setCurrency(order.getTotalAmount().getCurrency());
        entity.setPaymentMethod(order.getPaymentMethod());
        entity.setPaymentTransactionId(order.getPaymentTransactionId());
        entity.setShippingAddress(toEmbeddable(order.getShippingAddress()));

        for (OrderItem item : order.getItems()) {
            ProductEntity product = productsByBusinessId.get(item.getProductId());
            if (product == null) {
                throw new IllegalStateException("ProductEntity not preloaded for id: " + item.getProductId());
            }
            OrderItemEntity itemEntity = new OrderItemEntity();
            itemEntity.setProduct(product);
            itemEntity.setProductId(item.getProductId());
            itemEntity.setProductName(item.getProductName());
            itemEntity.setQuantity(item.getQuantity());
            itemEntity.setUnitPrice(item.getUnitPrice().getAmount());
            itemEntity.setTotalPrice(item.getTotalPrice().getAmount());
            entity.addItem(itemEntity);
        }
        return entity;
    }

    /**
     * Update path — preserves pk, id, createdAt (all immutable post-creation).
     * Items are cleared and re-added rather than diffed; orphanRemoval handles deletes.
     * Correct for this system's low item-count-per-order profile (avg ~2); would need
     * a real diff strategy if item counts were large.
     */
    public void updateEntity(OrderEntity entity, Order order, Map<String, ProductEntity> productsByBusinessId) {
        entity.setStatus(order.getStatus());
        entity.setPaymentMethod(order.getPaymentMethod());
        entity.setPaymentTransactionId(order.getPaymentTransactionId());
        entity.setShippingAddress(toEmbeddable(order.getShippingAddress()));
        entity.setConfirmedAt(order.getConfirmedAt());
        entity.setEstimatedDeliveryDate(order.getEstimatedDeliveryDate());

        entity.clearItems();
        for (OrderItem item : order.getItems()) {
            ProductEntity product = productsByBusinessId.get(item.getProductId());
            OrderItemEntity itemEntity = new OrderItemEntity();
            itemEntity.setProduct(product);
            itemEntity.setProductId(item.getProductId());
            itemEntity.setProductName(item.getProductName());
            itemEntity.setQuantity(item.getQuantity());
            itemEntity.setUnitPrice(item.getUnitPrice().getAmount());
            itemEntity.setTotalPrice(item.getTotalPrice().getAmount());
            entity.addItem(itemEntity);
        }
    }

    private ShippingAddressEmbeddable toEmbeddable(ShippingAddress addr) {
        if (addr == null) {
            return null;
        }
        return new ShippingAddressEmbeddable(
                addr.street(), addr.city(), addr.state(), addr.postalCode(), addr.country());
    }

    private ShippingAddress toDomain(ShippingAddressEmbeddable e) {
        if (e == null) {
            return null;
        }
        return new ShippingAddress(
                e.getStreet(), e.getCity(), e.getState(), e.getPostalCode(), e.getCountry());
    }
}