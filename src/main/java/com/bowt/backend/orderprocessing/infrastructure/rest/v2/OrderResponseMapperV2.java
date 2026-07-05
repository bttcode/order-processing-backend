package com.bowt.backend.orderprocessing.infrastructure.rest.v2;

import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class OrderResponseMapperV2 {

    public OrderResponseV2 toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        return new OrderResponseV2(
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
                order.getEstimatedDeliveryDate());
    }

    public OrderListResponseV2 toListResponse(QueryOrderUseCase.PagedResult result) {
        List<OrderResponseV2> content = result.content().stream().map(this::toResponse).toList();
        PageMetadata page = new PageMetadata(
                result.page(), result.size(), result.totalElements(), result.totalPages());
        return new OrderListResponseV2(content, page);
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getQuantity(), item.getUnitPrice().getAmount(), item.getTotalPrice().getAmount());
    }
}