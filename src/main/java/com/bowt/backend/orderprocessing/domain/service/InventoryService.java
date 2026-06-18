package com.bowt.backend.orderprocessing.domain.service;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.model.Product;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * Phase 2: parallel inventory check and atomic reservation.
 *
 * <h3>Concurrency strategy</h3>
 * <ol>
 *   <li>{@link #checkAvailabilityParallel} fans out one
 *       {@code CompletableFuture} per product via {@code inventoryCheckExecutor},
 *       then joins with {@code CompletableFuture.allOf()} (FR-2).</li>
 *   <li>{@link #reserveInventory} runs inside a single
 *       {@code REPEATABLE_READ} transaction and is annotated with
 *       {@code @Retryable(on = OptimisticLockException.class, maxAttempts = 3)}.
 *       When Hibernate detects a version conflict it throws
 *       {@code OptimisticLockException}; {@code RetryAspect} catches it and
 *       re-invokes the method up to 3 times (DB-2).</li>
 * </ol>
 *
 * <p><b>[OI-8]</b> {@code REPEATABLE_READ} is set <em>explicitly</em> on
 * {@link #reserveInventory} — it is not inherited from the calling transaction.
 *
 * <p><b>[OI-9]</b> {@code @Retryable} uses {@code Thread.sleep()} inside
 * {@code RetryAspect}. This method is intentionally synchronous. The reactive
 * path in {@code OrderService.processOrderReactive} drives inventory via
 * {@code Mono.fromCallable(() -> reserveInventory(...))} which offloads the
 * blocking call to the boundedElastic scheduler, keeping the reactive thread
 * non-blocked.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;

    // Injected by name — defined in ExecutorConfiguration
    @Qualifier("inventoryCheckExecutor")
    private final ThreadPoolExecutor inventoryCheckExecutor;

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fan-out: checks each product's availability in parallel.
     * Returns a map of productId → Product (with current stock snapshot).
     *
     * @throws InsufficientInventoryException if any product lacks stock
     * @throws IllegalArgumentException       if any productId is unknown
     */
    public Map<String, Product> checkAvailabilityParallel(
            List<CreateOrderCommand.OrderItemCommand> items) {

        // One CF per item, submitted to the bounded inventoryCheckExecutor
        List<CompletableFuture<Product>> futures = items.stream()
                .map(cmd -> CompletableFuture.supplyAsync(
                        () -> fetchAndVerify(cmd.productId(), cmd.quantity()),
                        inventoryCheckExecutor))
                .toList();

        // Wait for all; any exception propagates wrapped in CompletionException
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));

        try {
            all.get(); // blocks until all checks finish (or the first fails)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Inventory check interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InsufficientInventoryException ex) throw ex;
            if (cause instanceof IllegalArgumentException ex) throw ex;
            throw new IllegalStateException("Inventory check failed: " + cause.getMessage(), cause);
        }

        // Collect results — all futures are already done at this point
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    /**
     * Atomically decrements stock for every item in the order.
     *
     * <p>Runs in its own {@code REPEATABLE_READ} transaction.
     * {@code @Retryable} causes {@code RetryAspect} to retry on
     * {@code OptimisticLockException} up to 3 times with linear back-off.
     *
     * <p>If all 3 attempts fail, the exception propagates to the caller
     * ({@code OrderService}), which transitions the order to
     * {@code INSUFFICIENT_INVENTORY}.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(on = OptimisticLockException.class, delayMs = 50)
    public void reserveInventory(List<CreateOrderCommand.OrderItemCommand> items) {
        for (CreateOrderCommand.OrderItemCommand cmd : items) {
            Product product = productRepository.findById(cmd.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown product ID: " + cmd.productId()));

            if (!product.hasStock(cmd.quantity())) {
                throw new InsufficientInventoryException(product.getId(), cmd.quantity());
            }

            product.setInventoryQuantity(product.getInventoryQuantity() - cmd.quantity());
            productRepository.save(product);
            // Hibernate flushes here; if version changed since read → OptimisticLockException
            // → RetryAspect catches it → re-enters this method from scratch

            log.debug("Reserved {} units of {}, remaining {}",
                    cmd.quantity(), product.getId(), product.getInventoryQuantity());
        }
    }

    /**
     * Reverses a reservation — called on payment failure (BR-6).
     *
     * <p>Uses {@code REQUIRED} propagation so it joins the caller's transaction
     * when one exists (rollback path) or runs standalone when called from the
     * failure handler.
     */
    @Transactional
    public void releaseInventory(List<CreateOrderCommand.OrderItemCommand> items) {
        for (CreateOrderCommand.OrderItemCommand cmd : items) {
            productRepository.findById(cmd.productId()).ifPresent(product -> {
                product.setInventoryQuantity(product.getInventoryQuantity() + cmd.quantity());
                productRepository.save(product);
                log.info("Released {} units of {} (payment failure / cancellation)",
                        cmd.quantity(), product.getId());
            });
        }
    }

    /**
     * Converts the snapshot-based reservation into a hard deduction (FR-4).
     * Currently the same as {@link #reserveInventory} because Phase 1 already
     * does a hard deduct — this method exists as a named hook for Phase 4
     * when a two-phase reserve → confirm model may be introduced.
     */
    @Transactional
    public void confirmDeduction(List<CreateOrderCommand.OrderItemCommand> items) {
        log.debug("Inventory deduction confirmed for {} line items", items.size());
        // No-op in Phase 2: deduction already committed by reserveInventory.
        // Phase 4: if we move to soft-reserve, execute the hard UPDATE here.
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Fetches a single product and verifies stock — executed on inventoryCheckExecutor.
     */
    private Product fetchAndVerify(String productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown product ID: " + productId));

        if (!product.hasStock(quantity)) {
            throw new InsufficientInventoryException(product.getId(), quantity);
        }
        return product;
    }
}