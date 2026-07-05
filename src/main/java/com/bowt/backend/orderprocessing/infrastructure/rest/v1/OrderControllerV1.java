package com.bowt.backend.orderprocessing.infrastructure.rest.v1;

import com.bowt.backend.orderprocessing.application.port.in.CancelOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.CancelOrderRequest;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.CancellationResponse;
import com.bowt.backend.orderprocessing.infrastructure.rest.dto.CreateOrderRequest;
import com.bowt.backend.orderprocessing.infrastructure.rest.idempotency.IdempotencyHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [OI-14] v1 controller — DTO shape uses `totalAmount`, no page metadata. Shares the exact
 * same use-case interfaces as v2 (see OrderControllerV2); only the DTO/mapper layer differs.
 * DeprecationInterceptor (rest/ratelimit) attaches the Warning header to every /api/v1/ response.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders v1", description = "Order management endpoints (v1, current)")
@RequiredArgsConstructor
public class OrderControllerV1 {

    private final CreateOrderUseCase createOrderUseCase;
    private final QueryOrderUseCase queryOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final OrderResponseMapperV1 responseMapper;
    private final IdempotencyHandler idempotencyHandler;

    @PostMapping
    @Operation(summary = "Create a new order", description = "Validates inventory and authorizes payment",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Order created"),
                    @ApiResponse(responseCode = "200", description = "Idempotent replay — same key, same payload"),
                    @ApiResponse(responseCode = "400", description = "Invalid request or missing Idempotency-Key"),
                    @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different payload"),
                    @ApiResponse(responseCode = "422", description = "Inventory or payment failure")
            })
    public ResponseEntity<?> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @Parameter(description = "Client-supplied UUID; required", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader) {

        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);

        // [OI-18] IdempotencyHandler decides 201 (first call) vs 200 (replay) vs 409 (conflict).
        // The controller must not unconditionally return .created(...) — that was the I-18 bug.
        return idempotencyHandler.handle(
                idempotencyKey,
                request,
                () -> {
                    Order order = createOrderUseCase.createOrder(toCommand(request, idempotencyKey));
                    OrderResponseV1 body = responseMapper.toResponse(order);
                    URI location = URI.create("/api/v1/orders/" + order.getId());
                    return ResponseEntity.created(location).body(body);
                },
                OrderResponseV1.class);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Fetch an order by id",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Order found"),
                    @ApiResponse(responseCode = "404", description = "Order does not exist")
            })
    public ResponseEntity<OrderResponseV1> getOrder(@PathVariable UUID orderId) {
        return queryOrderUseCase.findById(orderId)
                .map(order -> ResponseEntity.ok(responseMapper.toResponse(order)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List orders for a customer", description = "Paginated, filterable order history")
    public ResponseEntity<List<OrderResponseV1>> listOrders(
            @RequestParam String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        QueryOrderUseCase.PagedResult result = queryOrderUseCase.findByCustomerIdAndFilters(
                customerId, OrderStatus.valueOf(status), from, to, OrderRepository.Pageable.of(page, size));

        List<OrderResponseV1> body = result.content().stream().map(responseMapper::toResponse).toList();
        return ResponseEntity.ok(body); // v1 intentionally omits page metadata
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order and issue a refund",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Cancelled"),
                    @ApiResponse(responseCode = "422", description = "Order not in a cancellable status")
            })
    public ResponseEntity<?> cancelOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody CancelOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader) {

        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);

        return idempotencyHandler.handle(
                idempotencyKey,
                Map.of("orderId", orderId.toString(), "reason", request.reason()),
                () -> {
                    var result = cancelOrderUseCase.cancelOrder(
                            new CancelOrderUseCase.CancelOrderCommand(orderId, request.reason(), idempotencyKey));
                    CancellationResponse body = new CancellationResponse(orderId.toString(), "CANCELLED",
                            result.getTotalAmount().getAmount(), result.getPaymentTransactionId());
                    return ResponseEntity.ok(body);
                },
                CancellationResponse.class);
    }

    private UUID parseIdempotencyKey(String header) {
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            // Malformed (non-UUID) Idempotency-Key — GlobalExceptionHandler maps this to
            // 400 invalid-request per API-2's error type catalogue.
            throw new IllegalArgumentException("Idempotency-Key header must be a valid UUID: " + header);
        }
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