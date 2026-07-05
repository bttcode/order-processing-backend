package com.bowt.backend.orderprocessing.infrastructure.rest.v2;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.CreateOrderRequest;
import com.bowt.backend.orderprocessing.infrastructure.rest.idempotency.IdempotencyHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * [OI-14] v2 controller. Shares CreateOrderUseCase / QueryOrderUseCase with v1 — the
 * domain and application layers do not change between versions; only this DTO/mapper
 * layer does. This is the concrete evidence artefact for LO-7's versioning skill check.
 */
@RestController
@RequestMapping("/api/v2/orders")
@Tag(name = "Orders v2", description = "Order management endpoints (v2 — simulated breaking change)")
@RequiredArgsConstructor
public class OrderControllerV2 {

    private final CreateOrderUseCase createOrderUseCase;
    private final QueryOrderUseCase queryOrderUseCase;
    private final OrderResponseMapperV2 responseMapper;
    private final IdempotencyHandler idempotencyHandler;

    @PostMapping
    @Operation(summary = "Create a new order (v2 response shape: `total` not `totalAmount`)")
    public ResponseEntity<?> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader) {

        UUID idempotencyKey = UUID.fromString(idempotencyKeyHeader);

        return idempotencyHandler.handle(
                idempotencyKey,
                request,
                () -> {
                    Order order = createOrderUseCase.createOrder(toCommand(request, idempotencyKey));
                    OrderResponseV2 body = responseMapper.toResponse(order);
                    return ResponseEntity.created(URI.create("/api/v2/orders/" + order.getId())).body(body);
                },
                OrderResponseV2.class);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponseV2> getOrder(@PathVariable UUID orderId) {
        return queryOrderUseCase.findById(orderId)
                .map(order -> ResponseEntity.ok(responseMapper.toResponse(order)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List orders (v2 adds explicit page metadata + from/to filters — see OI-14 table)")
    public ResponseEntity<OrderListResponseV2> listOrders(
            @RequestParam String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        QueryOrderUseCase.PagedResult result = queryOrderUseCase.findByCustomerIdAndFilters(
                customerId, OrderStatus.valueOf(status), from, to, OrderRepository.Pageable.of(page, size));

        return ResponseEntity.ok(responseMapper.toListResponse(result));
    }

    private CreateOrderCommand toCommand(CreateOrderRequest request, UUID idempotencyKey) {
        List<CreateOrderCommand.OrderItemCommand> items = request.items().stream()
                .map(i -> new CreateOrderCommand.OrderItemCommand(i.productId(), i.quantity()))
                .toList();
        var addr = request.shippingAddress();
        return new CreateOrderCommand(
                request.customerId(),
                items,
                PaymentMethod.valueOf(request.paymentMethod()),
                new CreateOrderCommand.ShippingAddressCommand(
                        addr.street(), addr.city(), addr.state(), addr.postalCode(), addr.country()),
                idempotencyKey
        );
    }
}