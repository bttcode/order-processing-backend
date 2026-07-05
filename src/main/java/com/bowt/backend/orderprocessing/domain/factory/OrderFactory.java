package com.bowt.backend.orderprocessing.domain.factory;

import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.ShippingAddress;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderType;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class OrderFactory {

    private static final int MAX_EXPRESS_LINE_ITEMS = 10;

    public Order createOrder(OrderType type, UUID id, OrderCreationRequest request) {
        if (type == null) {
            throw new IllegalArgumentException("OrderType must not be null");
        }
        validateForType(type, request);

        Order order = new Order(id, request.customerId());
        for (OrderItem item : request.items()) {
            order.addItem(item);
        }
        order.setPaymentMethod(request.paymentMethod());
        if (request.shippingAddress() != null) {
            order.setShippingAddress(request.shippingAddress());
        }
        return order;
    }

    private void validateForType(OrderType type, OrderCreationRequest request) {
        if (request.items().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        switch (type) {
            case STANDARD -> {
                // No additional rules beyond the base validation above.
            }
            case EXPRESS -> {
                if (request.items().size() > MAX_EXPRESS_LINE_ITEMS) {
                    throw new IllegalArgumentException(
                            "Express orders are limited to " + MAX_EXPRESS_LINE_ITEMS
                                    + " line items, got " + request.items().size());
                }
                if (request.shippingAddress() == null) {
                    throw new IllegalArgumentException(
                            "Express orders require a shipping address");
                }
            }
            case SUBSCRIPTION -> {
                if (request.shippingAddress() == null) {
                    throw new IllegalArgumentException(
                            "Subscription orders require a shipping address for recurring shipment");
                }
                if (request.paymentMethod() == null) {
                    throw new IllegalArgumentException(
                            "Subscription orders require a stored payment method for recurring billing");
                }
            }
        }
    }

    public record OrderCreationRequest(
            String customerId,
            List<OrderItem> items,
            PaymentMethod paymentMethod,
            ShippingAddress shippingAddress
    ) {
        public OrderCreationRequest {
            Objects.requireNonNull(customerId, "customerId must not be null");
            Objects.requireNonNull(items, "items must not be null");
            Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
            // shippingAddress may be null for order types that don't require one;
            items = List.copyOf(items); // defensive copy — command objects are immutable
        }
    }
}