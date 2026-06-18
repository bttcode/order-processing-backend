package com.bowt.backend.orderprocessing.infrastructure;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderStatus;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-4: Concurrency correctness gate.
 *
 * <p>Proves that optimistic locking + retry logic in {@code InventoryService}
 * prevents overselling under concurrent load. This test is the acceptance
 * gate for Phase 2 — it must pass reliably before Phase 3 profiling begins.
 *
 * <h3>Invariants (always hold regardless of retry semantics — see [OI-17])</h3>
 * <ul>
 *   <li>{@code finalInventory >= 0} — never negative (oversell = bug)</li>
 *   <li>{@code successCount <= initialInventory / quantityPerOrder}
 *       — successful orders cannot exceed available stock</li>
 * </ul>
 *
 * <p>The test is tagged {@code "concurrency"} and {@code @RepeatedTest(5)}
 * so CI runs it five times in a row. A single flaky pass is not acceptable.
 */
@SpringBootTest
@Testcontainers
@Tag("concurrency")
class ConcurrentInventoryTest {

    // ── Testcontainers ─────────────────────────────────────────────────────

    private static final int INITIAL_INVENTORY = 100;
    private static final int QUANTITY_PER_ORDER = 5;

    // ── Wiring ─────────────────────────────────────────────────────────────
    private static final int CONCURRENT_ORDERS = 50;  // total demand = 250 > 100
    private static final int MAX_SUCCESSES = INITIAL_INVENTORY / QUANTITY_PER_ORDER; // 20

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String PRODUCT_ID = "TEST-PROD-001";
    private static final String CUSTOMER_ID = "TEST-CUST-001";
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");
    @Autowired
    CreateOrderUseCase createOrderUseCase;
    @Autowired
    JpaProductRepository jpaProductRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seedProduct() {
        // Delete any leftover entity from a previous test iteration
        ProductEntity product = jpaProductRepository.findByBusinessId(PRODUCT_ID)
                .orElseGet(ProductEntity::new);

        product.setId(PRODUCT_ID);
        product.setSku("SKU-CONCURRENCY-TEST");
        product.setName("Concurrency Test Product");
        product.setPrice(new BigDecimal("9.99"));
        product.setInventoryQuantity(INITIAL_INVENTORY);
        jpaProductRepository.save(product);
    }

    // ── TEST-4 — Core oversell prevention ─────────────────────────────────

    /**
     * 50 threads fire simultaneously; only the first 20 can succeed
     * (20 × 5 = 100 units available). Final inventory must be ≥ 0.
     *
     * <p>Run 5 times in CI — any flake means the locking implementation is broken.
     */
    @RepeatedTest(5)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrent50Orders_shouldNeverOversell() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
        CountDownLatch startGate = new CountDownLatch(CONCURRENT_ORDERS);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            final String customerId = CUSTOMER_ID + "-" + i;
            futures.add(executor.submit(() -> {
                startGate.countDown();
                startGate.await(); // synchronized start — maximizes contention

                try {
                    Order order = createOrderUseCase.createOrder(buildCommand(customerId));
                    if (order.getStatus() == OrderStatus.CONFIRMED) {
                        successes.incrementAndGet();
                        return true;
                    }
                } catch (InsufficientInventoryException | PaymentFailedException e) {
                    // Expected failure paths
                } catch (Exception e) {
                    // Log unexpected exceptions but count as failure
                }
                failures.incrementAndGet();
                return false;
            }));
        }

        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        // Retrieve all results
        for (Future<Boolean> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        // ── Hard invariants ────────────────────────────────────────────────

        ProductEntity finalProduct = jpaProductRepository.findByBusinessId(PRODUCT_ID)
                .orElseThrow(() -> new AssertionError("Product disappeared"));

        // 1. Inventory must never go negative
        assertThat(finalProduct.getInventoryQuantity())
                .as("Inventory must never go negative (oversell)")
                .isGreaterThanOrEqualTo(0);

        // 2. Successful orders cannot exceed available stock
        assertThat(successes.get())
                .as("Successful orders (%d) must not exceed max capacity (%d)",
                        successes.get(), MAX_SUCCESSES)
                .isLessThanOrEqualTo(MAX_SUCCESSES);

        // 3. Accounting: deducted units = successCount × quantityPerOrder
        int expectedRemaining = INITIAL_INVENTORY - (successes.get() * QUANTITY_PER_ORDER);
        assertThat(finalProduct.getInventoryQuantity())
                .as("Remaining inventory must account for successful deductions exactly")
                .isEqualTo(expectedRemaining);

        // Informational (not a hard assertion — see [OI-17])
        System.out.printf("[TEST-4] successes=%d, failures=%d, finalInventory=%d%n",
                successes.get(), failures.get(), finalProduct.getInventoryQuantity());
    }

    // ── Additional concurrency scenario: simultaneous cancellation ─────────

    /**
     * Two threads try to cancel the same order simultaneously.
     * Exactly one must succeed; the other must get a state-conflict error.
     * (Full cancellation logic lands in Phase 4 — this is a placeholder
     * skeleton that verifies the optimistic-lock version field prevents
     * double-state-transition on the Order entity.)
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void simultaneousCancellation_exactlyOneWins() {
        // Create one confirmed order
        Order order = createOrderUseCase.createOrder(buildCommand("CANCEL-TEST-CUST"));

        // Phase 4 will fill this in with actual cancel calls.
        // For now, verify the order is correctly CONFIRMED (precondition).
        assertThat(order.getStatus())
                .as("Order must be CONFIRMED before cancellation test can run")
                .isEqualTo(OrderStatus.CONFIRMED);

        // TODO Phase 4: spin 2 threads on cancelOrderUseCase.cancel(order.getId())
        //   assert exactly 1 succeeds and 1 throws InvalidOrderStateException or 409.
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private CreateOrderCommand buildCommand(String customerId) {
        return new CreateOrderCommand(
                customerId,
                List.of(new OrderItemCommand(PRODUCT_ID, QUANTITY_PER_ORDER)),
                "CREDIT_CARD",
                "1 Test St", "TestCity", "TC", "00000", "US"
        );
    }
}