package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import com.bowt.backend.orderprocessing.domain.model.Product;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@Service
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final ThreadPoolExecutor inventoryCheckExecutor;

    public InventoryService(
            ProductRepository productRepository,
            @Qualifier("inventoryCheckExecutor") ThreadPoolExecutor inventoryCheckExecutor) {
        this.productRepository = productRepository;
        this.inventoryCheckExecutor = inventoryCheckExecutor;
    }

    /**
     * FR-2: parallel availability check — {@code CompletableFuture.allOf()}
     * fans out one query per product onto {@code inventoryCheckExecutor}
     * (AbortPolicy — fail fast under saturation; caller treats a rejection as
     * "system overloaded", not "insufficient stock").
     */
    public Map<String, Boolean> checkAvailabilityParallel(List<OrderItemCommand> items) {
        List<CompletableFuture<AbstractMap.SimpleEntry<String, Boolean>>> futures = items.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> {
                    Product product = productRepository.findById(item.productId())
                            .orElseThrow(() -> new ProductNotFoundException(item.productId()));
                    boolean available = product.isAvailable(item.quantity());
                    return new AbstractMap.SimpleEntry<>(item.productId(), available);
                }, inventoryCheckExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * FR-2 / BR-3: atomically reserve stock for every item. Retried by
     * {@code RetryAspect} on {@link OptimisticLockException} (ADR-003,
     * max 3 attempts, linear back-off) — see {@code domain.annotation.Retryable}.
     * If all attempts on any item are exhausted, the exception propagates and
     * {@code OrderService} transitions the order to
     * {@code INSUFFICIENT_INVENTORY}.
     * <p>
     * Domain-owned stock arithmetic: uses {@code Product.deductStock(int)}
     * (P9 fix) rather than a raw setter, so the &gt;=0 invariant is enforced
     * inside the aggregate, not scattered across services.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public void reserveInventory(List<OrderItemCommand> items) {
        for (OrderItemCommand item : items) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new ProductNotFoundException(item.productId()));
            product.deductStock(item.quantity());
            productRepository.save(product); // version check fires on flush — OptimisticLockException on conflict
        }
        log.debug("Reserved inventory for {} line item(s)", items.size());
    }

    /**
     * BR-6 compensation path: releases a reservation when payment fails or
     * the order is cancelled while {@code PAYMENT_AUTHORIZED} ([OI-5]).
     * Independent transaction — must succeed even if the triggering failure
     * already rolled back the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseInventory(List<OrderItemCommand> items) {
        for (OrderItemCommand item : items) {
            productRepository.findById(item.productId()).ifPresentOrElse(
                    product -> {
                        product.restoreStock(item.quantity());
                        productRepository.save(product);
                    },
                    () -> log.warn("Cannot release inventory — product {} no longer exists", item.productId())
            );
        }
        log.debug("Released inventory for {} line item(s)", items.size());
    }
}