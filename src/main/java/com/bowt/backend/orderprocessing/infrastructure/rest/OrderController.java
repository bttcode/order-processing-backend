package com.bowt.backend.orderprocessing.infrastructure.rest;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.CreateOrderRequest;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderItemResponse;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final QueryOrderUseCase queryOrderUseCase;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {

        CreateOrderCommand command = new CreateOrderCommand(
                request.customerId(),
                request.items().stream()
                        .map(i ->
                                new CreateOrderCommand.OrderItemCommand(i.productId(), i.quantity()))
                        .toList(),
                request.paymentMethod(),
                request.shippingAddress().street(),
                request.shippingAddress().city(),
                request.shippingAddress().state(),
                request.shippingAddress().postalCode(),
                request.shippingAddress().country()
        );

        Order order = createOrderUseCase.createOrder(command);
        OrderResponse response = toResponse(order);

        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + order.getId()))
                .body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return queryOrderUseCase.findById(orderId)
                .map(order -> ResponseEntity.ok(toResponse(order)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Private mapper (controller → response DTO) ────────────────────────
    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(
                        i.getProductId(),
                        i.getProductName(),
                        i.getQuantity(),
                        i.getUnitPrice().getAmount(),
                        i.getTotalPrice().getAmount()
                ))
                .toList();

        return new OrderResponse(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount().getAmount(),
                order.getTotalAmount().getCurrency(),
                order.getPaymentMethod(),
                order.getPaymentTransactionId(),
                order.getCreatedAt(),
                order.getConfirmedAt(),
                order.getEstimatedDeliveryDate(),
                items
        );
    }
}
