package com.bowt.backend.orderprocessing.infrastructure.rest.v1;

import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderItemResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OrderResponseMapperV1 {

    public OrderResponseV1 toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        String base = "/api/v1/orders/" + order.getId();
        Map<String, Map<String, String>> links = Map.of(
                "self", Map.of("href", base),
                "cancel", Map.of("href", base + "/cancel"));

        return new OrderResponseV1(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStatus().name(),
                items,
                order.getTotalAmount().getAmount(),
                order.getTotalAmount().getCurrency().name(),
                order.getPaymentMethod().name(),
                order.getPaymentTransactionId(),
                order.getCreatedAt(),
                order.getConfirmedAt(),
                order.getEstimatedDeliveryDate(),
                links);
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getQuantity(), item.getUnitPrice().getAmount(), item.getTotalPrice().getAmount());
    }
}