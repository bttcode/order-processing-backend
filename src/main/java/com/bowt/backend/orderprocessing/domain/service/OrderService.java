package com.bowt.backend.orderprocessing.domain.service;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.Product;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 1: synchronous, single-threaded order pipeline.
 * No Spring annotations on this class — it is a pure domain service.
 *
 * @Transactional lives in UseCaseConfiguration where this bean is wired.
 * <p>
 * NOTE: In Phase 4 this becomes CreateOrderUseCase + QueryOrderUseCase
 * implementations in the application layer. For Phase 1 we keep it simple —
 * one service class, registered as both use cases via UseCaseConfiguration.
 */
@RequiredArgsConstructor
public class OrderService implements CreateOrderUseCase, QueryOrderUseCase {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentGateway paymentGateway;

    @Override
    public Order createOrder(CreateOrderCommand command) {
        // 1. Build the domain Order
        Order order = new Order(command.customerId());
        order.setPaymentMethod(command.paymentMethod());

        // 2. Resolve products, validate existence, build items
        List<ItemWithProduct> resolved = resolveItems(command.items());

        // 3. Check inventory and build OrderItems (Phase 1: sequential, no locking)
        for (ItemWithProduct iwp : resolved) {
            Product product = iwp.product();
            int qty = iwp.command().quantity();

            if (!product.hasStock(qty)) {
                order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
                orderRepository.save(order);
                throw new InsufficientInventoryException(product.getId(), qty);
            }
            order.addItem(new OrderItem(
                    product.getId(),
                    product.getName(),
                    qty,
                    product.getPrice()
            ));
        }

        // 4. Deduct inventory (Phase 1: simple decrement — no optimistic lock yet)
        for (ItemWithProduct iwp : resolved) {
            Product p = iwp.product();
            p.setInventoryQuantity(p.getInventoryQuantity() - iwp.command().quantity());
            productRepository.save(p);
        }

        // 5. Transition to PENDING_PAYMENT and persist
        order.transitionTo(OrderStatus.PENDING_PAYMENT);
        orderRepository.save(order);

        // 6. Authorize payment
        PaymentGateway.PaymentResult result = paymentGateway.authorize(
                new PaymentGateway.PaymentRequest(
                        order.getId().toString(),
                        order.getTotalAmount(),
                        order.getPaymentMethod()
                )
        );

        if (!result.success()) {
            order.transitionTo(OrderStatus.PAYMENT_FAILED);
            // Restore inventory (BR-6 — Phase 1: inline; Phase 2: async)
            restoreInventory(resolved);
            orderRepository.save(order);
            throw new PaymentFailedException(result.failureReason());
        }

        // 7. Confirm
        order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
        order.confirm(result.transactionId()); // → sets CONFIRMED + confirmedAt + delivery date

        return orderRepository.save(order);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private List<ItemWithProduct> resolveItems(List<CreateOrderCommand.OrderItemCommand> commands) {
        List<ItemWithProduct> result = new ArrayList<>();
        for (var cmd : commands) {
            Product product = productRepository.findById(cmd.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown product ID: " + cmd.productId()));
            result.add(new ItemWithProduct(cmd, product));
        }
        return result;
    }

    private void restoreInventory(List<ItemWithProduct> items) {
        for (ItemWithProduct iwp : items) {
            Product p = iwp.product();
            p.setInventoryQuantity(p.getInventoryQuantity() + iwp.command().quantity());
            productRepository.save(p);
        }
    }

    private record ItemWithProduct(
            CreateOrderCommand.OrderItemCommand command,
            Product product
    ) {
    }
}
