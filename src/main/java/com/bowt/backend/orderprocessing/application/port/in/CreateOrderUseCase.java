package com.bowt.backend.orderprocessing.application.port.in;

import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CreateOrderUseCase {

    @Transactional
    Order createOrder(CreateOrderCommand command);

    record CreateOrderCommand(
            String customerId,
            List<OrderItemCommand> items,
            PaymentMethod paymentMethod,
            ShippingAddressCommand shippingAddress,
            UUID idempotencyKey // required header, validated at the controller/interceptor boundary
    ) {
        public CreateOrderCommand {
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("Order must contain at least one item");
            }
        }

        public record OrderItemCommand(String productId, int quantity) {
            public OrderItemCommand {
                if (quantity <= 0) {
                    throw new IllegalArgumentException("Quantity must be positive: " + quantity);
                }
            }
        }

        public record ShippingAddressCommand(
                String street,
                String city,
                String state,
                String postalCode,
                String country
        ) {
        }
    }
}