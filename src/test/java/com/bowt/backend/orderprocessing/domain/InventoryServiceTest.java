package com.bowt.backend.orderprocessing.domain;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.domain.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InventoryService}.
 * No Spring context — plain JUnit 5 + Mockito.
 * <p>
 * NOTE: checkAvailabilityParallel uses a real ThreadPoolExecutor (injected
 * inline) so we exercise the CompletableFuture fan-out without needing
 * a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    ProductRepository productRepository;

    InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        // Small inline executor for unit tests — no Spring needed
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy());

        inventoryService = new InventoryService(productRepository, executor);
    }

    // ── checkAvailabilityParallel ──────────────────────────────────────────

    @Test
    void checkAvailability_allInStock_returnsProductMap() {
        Product p1 = product("PROD-1", 50);
        Product p2 = product("PROD-2", 30);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.findById("PROD-2")).thenReturn(Optional.of(p2));

        var items = List.of(new OrderItemCommand("PROD-1", 10), new OrderItemCommand("PROD-2", 5));
        var result = inventoryService.checkAvailabilityParallel(items);

        assertThat(result).hasSize(2);
        assertThat(result.get("PROD-1").getInventoryQuantity()).isEqualTo(50);
        assertThat(result.get("PROD-2").getInventoryQuantity()).isEqualTo(30);
    }

    @Test
    void checkAvailability_productOutOfStock_throwsInsufficientInventory() {
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product("PROD-1", 3)));

        var items = List.of(new OrderItemCommand("PROD-1", 10));

        assertThatThrownBy(() -> inventoryService.checkAvailabilityParallel(items))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("PROD-1");
    }

    @Test
    void checkAvailability_unknownProduct_throwsIllegalArgument() {
        when(productRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        var items = List.of(new OrderItemCommand("UNKNOWN", 1));

        assertThatThrownBy(() -> inventoryService.checkAvailabilityParallel(items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void checkAvailability_oneProductFailsAmongMany_throwsImmediately() {
        Product ok = product("PROD-OK", 100);
        Product short_ = product("PROD-SHORT", 0);
        when(productRepository.findById("PROD-OK")).thenReturn(Optional.of(ok));
        when(productRepository.findById("PROD-SHORT")).thenReturn(Optional.of(short_));

        var items = List.of(
                new OrderItemCommand("PROD-OK", 5),
                new OrderItemCommand("PROD-SHORT", 1));

        assertThatThrownBy(() -> inventoryService.checkAvailabilityParallel(items))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    // ── reserveInventory ──────────────────────────────────────────────────

    @Test
    void reserveInventory_sufficientStock_decrementsAndSaves() {
        Product p = product("PROD-1", 20);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveInventory(List.of(new OrderItemCommand("PROD-1", 5)));

        assertThat(p.getInventoryQuantity()).isEqualTo(15);
        verify(productRepository, times(1)).save(p);
    }

    @Test
    void reserveInventory_insufficientStock_throwsWithoutSaving() {
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product("PROD-1", 2)));

        assertThatThrownBy(() ->
                inventoryService.reserveInventory(List.of(new OrderItemCommand("PROD-1", 5)))
        ).isInstanceOf(InsufficientInventoryException.class);

        verify(productRepository, never()).save(any());
    }

    // ── releaseInventory ──────────────────────────────────────────────────

    @Test
    void releaseInventory_restoresQuantity() {
        Product p = product("PROD-1", 10);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.releaseInventory(List.of(new OrderItemCommand("PROD-1", 5)));

        assertThat(p.getInventoryQuantity()).isEqualTo(15);
        verify(productRepository, times(1)).save(p);
    }

    @Test
    void releaseInventory_unknownProduct_silentlyIgnored() {
        when(productRepository.findById("GHOST")).thenReturn(Optional.empty());

        // Must not throw — release is best-effort on a missing product
        assertThatNoException().isThrownBy(() ->
                inventoryService.releaseInventory(List.of(new OrderItemCommand("GHOST", 3))));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private Product product(String id, int qty) {
        return new Product(id, "SKU-" + id, "Product " + id,
                Money.of("9.99"), qty, 0);
    }
}