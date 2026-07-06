package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Application-layer unit test for {@link InventoryService}.
 * Mocks the out-port ({@link ProductRepository}) only — no Spring context,
 * per TEST-1/tree-test.md conventions.
 * <p>
 * NOTE ON SCOPE: {@code @Transactional} and {@code @Retryable} are AOP
 * concerns applied by Spring proxies at runtime. A plain unit test on this
 * class never goes through a proxy, so:
 * - REPEATABLE_READ / REQUIRES_NEW semantics are NOT verified here
 * (see isolation/RepeatableReadInventoryTest instead).
 * - Retry-on-OptimisticLockException is NOT verified here
 * (see infrastructure/config/RetryAspectTest instead).
 * This test only verifies the orchestration logic InventoryService itself
 * is responsible for.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ThreadPoolExecutor inventoryCheckExecutor;
    private InventoryService inventoryService;

    private static Product product(String id, int qty) {
        return new Product(id, "SKU-" + id, "Product " + id, Money.of(9.99), qty);
    }

    private static OrderItemCommand item(String productId, int quantity) {
        return new OrderItemCommand(productId, quantity);
    }

    @BeforeEach
    void setUp() {
        // Real (small) executor rather than a mock — checkAvailabilityParallel
        // actually submits Runnables to it; mocking it would just test Mockito.
        inventoryCheckExecutor = new ThreadPoolExecutor(
                2, 2, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(10));
        inventoryService = new InventoryService(productRepository, inventoryCheckExecutor);
    }

    @AfterEach
    void tearDown() {
        inventoryCheckExecutor.shutdownNow();
    }

    // ────────────────────────────────────────────────────────────────
    // checkAvailabilityParallel (FR-2 parallel read)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkAvailabilityParallel: all products in stock -> map of true for every productId")
    void checkAvailabilityParallel_allAvailable_returnsTrueForEach() {
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product("PROD-1", 10)));
        when(productRepository.findById("PROD-2")).thenReturn(Optional.of(product("PROD-2", 3)));

        Map<String, Boolean> result = inventoryService.checkAvailabilityParallel(
                List.of(item("PROD-1", 5), item("PROD-2", 3)));

        assertThat(result)
                .hasSize(2)
                .containsEntry("PROD-1", true)
                .containsEntry("PROD-2", true);
    }

    @Test
    @DisplayName("checkAvailabilityParallel: one product short on stock -> false only for that entry")
    void checkAvailabilityParallel_insufficientStock_returnsFalseForShortItem() {
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product("PROD-1", 10)));
        when(productRepository.findById("PROD-2")).thenReturn(Optional.of(product("PROD-2", 2)));

        Map<String, Boolean> result = inventoryService.checkAvailabilityParallel(
                List.of(item("PROD-1", 5), item("PROD-2", 5))); // PROD-2 wants 5, has 2

        assertThat(result)
                .containsEntry("PROD-1", true)
                .containsEntry("PROD-2", false);
    }

    @Test
    @DisplayName("checkAvailabilityParallel: unknown product -> CompletionException wrapping ProductNotFoundException")
    void checkAvailabilityParallel_unknownProduct_wrapsProductNotFoundException() {
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product("PROD-1", 10)));
        when(productRepository.findById("PROD-GHOST")).thenReturn(Optional.empty());

        // The lookup runs inside CompletableFuture.supplyAsync — join() rethrows
        // as CompletionException, NOT the raw ProductNotFoundException. Callers
        // (and future tests) must unwrap via getCause(), not catch it directly.
        assertThatThrownBy(() -> inventoryService.checkAvailabilityParallel(
                List.of(item("PROD-1", 1), item("PROD-GHOST", 1))))
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("checkAvailabilityParallel: fans out onto the injected executor, not the calling thread")
    void checkAvailabilityParallel_usesInjectedExecutor() {
        when(productRepository.findById(any())).thenAnswer(inv ->
                Optional.of(product(inv.getArgument(0), 100)));

        inventoryService.checkAvailabilityParallel(
                List.of(item("PROD-1", 1),
                        item("PROD-2", 1),
                        item("PROD-3", 1)));

        // completedTaskCount is a reliable post-hoc signal that work actually
        // went through this executor rather than being run inline.
        assertThat(inventoryCheckExecutor.getCompletedTaskCount()).isEqualTo(3L);
    }

    // ────────────────────────────────────────────────────────────────
    // reserveInventory (FR-2 / BR-3 write path)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reserveInventory: deducts stock and saves every line item")
    void reserveInventory_happyPath_deductsAndSavesEachProduct() {
        Product p1 = product("PROD-1", 10);
        Product p2 = product("PROD-2", 5);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.findById("PROD-2")).thenReturn(Optional.of(p2));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveInventory(List.of(item("PROD-1", 4), item("PROD-2", 5)));

        assertThat(p1.getInventoryQuantity()).isEqualTo(6);   // 10 - 4
        assertThat(p2.getInventoryQuantity()).isEqualTo(0);   // 5 - 5, exact depletion allowed
        verify(productRepository).save(p1);
        verify(productRepository).save(p2);
    }

    @Test
    @DisplayName("reserveInventory: unknown product propagates ProductNotFoundException and stops further processing")
    void reserveInventory_productNotFound_propagatesAndStopsAtThatItem() {
        Product p1 = product("PROD-1", 10);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.findById("PROD-GHOST")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> inventoryService.reserveInventory(
                List.of(item("PROD-1", 4),
                        item("PROD-GHOST", 1),
                        item("PROD-1", 1))))
                .isInstanceOf(ProductNotFoundException.class);

        // PROD-1 (first item) was already processed and saved before the failure —
        // this method has no transaction boundary of its own in a unit test;
        // real atomicity across items only exists because @Transactional wraps
        // the whole method in production (verified in integration tests, not here).
        verify(productRepository, times(1)).save(p1);
        // The third item (another PROD-1) must never be reached.
        verify(productRepository, times(1)).findById("PROD-1");
    }

    @Test
    @DisplayName("reserveInventory: KNOWN GAP — insufficient stock does not throw or block the save (BR-3 risk)")
    void reserveInventory_whenStockInsufficient_currentlyDoesNotSignalFailure() {
        // Product.deductStock() returns false when requested > available and
        // leaves inventoryQuantity unchanged. InventoryService.reserveInventory
        // discards that boolean, so today this call completes silently instead
        // of throwing InsufficientInventoryException or signalling failure.
        // If this test starts failing because an exception IS now thrown,
        // that means the gap was fixed — update this test to assert the new,
        // correct behaviour instead of reverting the fix.
        Product p1 = product("PROD-1", 2);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveInventory(List.of(item("PROD-1", 5))); // wants 5, has 2

        assertThat(p1.getInventoryQuantity())
                .as("deductStock leaves quantity unchanged on failed deduction")
                .isEqualTo(2);
        verify(productRepository).save(p1); // saved anyway — no signal reaches the caller
    }

    // ────────────────────────────────────────────────────────────────
    // releaseInventory (BR-6 compensation path)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("releaseInventory: restores stock and saves every existing product")
    void releaseInventory_happyPath_restoresAndSaves() {
        Product p1 = product("PROD-1", 6);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.releaseInventory(List.of(item("PROD-1", 4)));

        assertThat(p1.getInventoryQuantity()).isEqualTo(10);
        verify(productRepository).save(p1);
    }

    @Test
    @DisplayName("releaseInventory: missing product is skipped without throwing")
    void releaseInventory_missingProduct_skipsSilently() {
        when(productRepository.findById("PROD-GHOST")).thenReturn(Optional.empty());

        inventoryService.releaseInventory(List.of(item("PROD-GHOST", 3)));

        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("releaseInventory: mixed batch — existing product restored, missing product skipped, no exception")
    void releaseInventory_partiallyMissing_processesFoundAndSkipsMissing() {
        Product p1 = product("PROD-1", 6);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(p1));
        when(productRepository.findById("PROD-GHOST")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.releaseInventory(List.of(
                item("PROD-1", 2),
                item("PROD-GHOST", 9)));

        assertThat(p1.getInventoryQuantity()).isEqualTo(8);
        verify(productRepository, times(1)).save(any(Product.class));
        verify(productRepository, never()).save(eq(null));
    }
}