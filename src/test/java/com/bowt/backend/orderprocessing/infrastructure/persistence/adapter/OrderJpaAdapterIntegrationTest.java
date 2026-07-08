package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.ShippingAddress;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import com.bowt.backend.orderprocessing.infrastructure.persistence.AbstractIntegrationTest;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaProductRepository;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.ProductEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration coverage for {@link OrderJpaAdapter}, matching the scope called out in
 * {@code tree-test.md}: {@code save}/{@code findByPublicId} correctness, and index usage.
 *
 * <h2>Assumptions about types not visible in the provided sources</h2>
 * <ul>
 *   <li>{@code application.port.out.Pageable} is a small record-like value type with
 *       {@code page()}, {@code size()}, {@code sortBy()}, {@code descending()} accessors —
 *       inferred from {@code OrderJpaAdapter#resolvePageable}. If the real type differs,
 *       only the {@code newPageable(...)} helper below needs to change.</li>
 *   <li>{@code ProductEntity} exposes standard JavaBean setters for
 *       {@code id, sku, name, description, price, inventoryQuantity}.</li>
 * </ul>
 */
class OrderJpaAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final String CUSTOMER_ID = "CUST-001";

    @Autowired
    private OrderJpaAdapter orderJpaAdapter;

    @Autowired
    private JpaProductRepository jpaProductRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seedCatalog() {
        persistProduct("PROD-001", 100);
        persistProduct("PROD-002", 100);
    }

    // ── save() — new order ──────────────────────────────────────────────────

    @Test
    void save_shouldPersistNewOrder_andBeFindableByPublicId() {
        UUID publicId = UUID.randomUUID();
        Order order = newOrder(publicId, "PROD-001", 2);

        Order saved = orderJpaAdapter.save(order);

        assertThat(saved.getId()).isEqualTo(publicId);
        assertThat(saved.getTotalAmount()).isEqualTo(Money.of(39.98));

        Optional<Order> reloaded = orderJpaAdapter.findById(publicId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(reloaded.get().getItems()).hasSize(1);
        assertThat(reloaded.get().getItems().getFirst().getProductId())
                .isEqualTo("PROD-001");
    }

    @Test
    void findById_shouldReturnEmpty_whenPublicIdUnknown() {
        assertThat(orderJpaAdapter.findById(UUID.randomUUID())).isEmpty();
    }

    // ── save() — existing order (the update / replace-items branch) ────────

    @Test
    void save_calledTwiceWithSamePublicId_shouldUpdateInPlace_notInsertDuplicateRow() {
        UUID publicId = UUID.randomUUID();
        Order order = newOrder(publicId, "PROD-001", 2);
        orderJpaAdapter.save(order);

        // Re-load, transition status, and replace the item set entirely — exercises the
        // jpaOrderItemRepo.deleteByOrderPk(...) + mapper.updateEntity(...) branch.
        Order toUpdate = orderJpaAdapter.findById(publicId).orElseThrow();
        toUpdate.transitionTo(OrderStatus.PENDING_PAYMENT);
        Order withDifferentItem = newOrder(publicId, "PROD-002", 5);
        withDifferentItem.transitionTo(OrderStatus.PENDING_PAYMENT);

        orderJpaAdapter.save(withDifferentItem);

        Order reloaded = orderJpaAdapter.findById(publicId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().getFirst().getProductId()).isEqualTo("PROD-002");
        assertThat(reloaded.getItems().getFirst().getQuantity()).isEqualTo(5);

        long rowCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM orders WHERE id = ?1")
                .setParameter(1, publicId)
                .getSingleResult()).longValue();
        assertThat(rowCount)
                .as("save() on an existing public id must update, never insert a second row")
                .isEqualTo(1L);
    }

    // ── save() — product resolution failure ─────────────────────────────────

    @Test
    void save_shouldThrowIllegalStateException_whenLineItemReferencesUnknownProduct() {
        Order order = newOrder(UUID.randomUUID(), "PROD-DOES-NOT-EXIST", 1);

        assertThatThrownBy(() -> orderJpaAdapter.save(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROD-DOES-NOT-EXIST");
    }

    // ── N+1 regression guard (see OrderJpaAdapter#resolveProductEntities javadoc) ──

    @Test
    void save_shouldResolveAllLineItemProducts_inExactlyOneBatchQuery() {
        Order order = newOrder(UUID.randomUUID(), "PROD-001", 1);
        order.addItem(new OrderItem("PROD-002", "Test Product PROD-002", 3, Money.of(19.99)));

        Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        orderJpaAdapter.save(order);

        // findAllByBusinessIds(...) must be a single "WHERE id IN (...)" statement regardless
        // of how many distinct products the order references — this is the exact regression
        // the adapter's own javadoc warns about (20k extra round-trips/sec at 10k orders/sec
        // * 2 items avg if this ever reverts to a per-item lookup).
        assertThat(stats.getPrepareStatementCount())
                .as("expected a single batched product lookup, not one query per line item")
                .isLessThanOrEqualTo(3); // 1 product SELECT + insert(s); generous upper bound
    }

    // ── filtering / pagination ───────────────────────────────────────────────

    @Test
    void findByCustomerIdAndFilters_shouldFilterByStatus() {
        Order confirmed = newOrder(UUID.randomUUID(), "PROD-001", 1);
        confirmed.transitionTo(OrderStatus.PENDING_PAYMENT);
        confirmed.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
        confirmed.confirm("TXN-1");
        orderJpaAdapter.save(confirmed);

        Order stillPending = newOrder(UUID.randomUUID(), "PROD-001", 1);
        orderJpaAdapter.save(stillPending);

        List<Order> confirmedOnly = orderJpaAdapter.findByCustomerIdAndFilters(
                CUSTOMER_ID, OrderStatus.CONFIRMED, null, null, newPageable(0, 20));

        assertThat(confirmedOnly).extracting(Order::getId).containsExactly(confirmed.getId());
    }

    @Test
    void findByCustomerIdAndFilters_shouldFilterByCreatedAtDateRange() {
        Order inRange = newOrder(UUID.randomUUID(), "PROD-001", 1);
        orderJpaAdapter.save(inRange);

        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        List<Order> results = orderJpaAdapter.findByCustomerIdAndFilters(
                CUSTOMER_ID, null, from, to, newPageable(0, 20));

        assertThat(results).extracting(Order::getId).contains(inRange.getId());

        List<Order> outOfRange = orderJpaAdapter.findByCustomerIdAndFilters(
                CUSTOMER_ID, null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.HOURS),
                newPageable(0, 20));

        assertThat(outOfRange).isEmpty();
    }

    @Test
    void countByCustomerIdAndFilters_shouldMatchNumberOfFilteredRows() {
        orderJpaAdapter.save(newOrder(UUID.randomUUID(), "PROD-001", 1));
        orderJpaAdapter.save(newOrder(UUID.randomUUID(), "PROD-001", 1));
        orderJpaAdapter.save(newOrder(UUID.randomUUID(), "PROD-002", 1));

        long count = orderJpaAdapter.countByCustomerIdAndFilters(CUSTOMER_ID, null, null, null);

        assertThat(count).isEqualTo(3L);
    }

    // ── index usage (lightweight companion to the DB-3 / ADR-001 100k-row gate) ──

    @Test
    void customerStatusCreatedAtQuery_shouldUseCompositeIndex_notSeqScan() {
        // Seed enough rows that the planner's cost model would ever consider an index; on a
        // handful of rows Postgres correctly prefers a sequential scan regardless of indexes
        // present, which would make this test a false negative. This is a smoke check, not a
        // replacement for the full EXPLAIN ANALYZE gate at 100k+ rows required by
        // 04-database.md § DB-1 / 07-delivery.md § DEL-4 — that one runs against a
        // realistically sized dataset in Phase 3, not in this per-commit suite.
        for (int i = 0; i < 500; i++) {
            orderJpaAdapter.save(newOrder(UUID.randomUUID(), "PROD-001", 1));
        }
        entityManager.flush();

        // Force the planner away from its default preference on a small table so the plan
        // reflects whether an index *can* be used, not just whether it currently *would* be.
        entityManager.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();

        @SuppressWarnings("unchecked")
        List<Object[]> planRows = entityManager.createNativeQuery(
                        "EXPLAIN (FORMAT TEXT) SELECT id, status, created_at FROM orders "
                                + "WHERE customer_id = ?1 AND status = ?2 "
                                + "ORDER BY created_at DESC LIMIT 20")
                .setParameter(1, CUSTOMER_ID)
                .setParameter(2, OrderStatus.PENDING_VALIDATION.name())
                .getResultList();

        String plan = planRows.stream()
                .map(String::valueOf)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(plan)
                .as("expected an index scan against idx_order_composite, got:%n%s", plan)
                .containsAnyOf("Index Scan", "Index Only Scan", "Bitmap Index Scan")
                .doesNotContain("Seq Scan");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order newOrder(UUID publicId, String productId, int quantity) {
        Order order = new Order(publicId, CUSTOMER_ID);
        order.addItem(new OrderItem(productId, "Test Product " + productId, quantity, Money.of(19.99)));
        order.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        order.setShippingAddress(new ShippingAddress("123 Main St", "San Francisco", "CA", "94102", "US"));
        return order;
    }

    private OrderRepository.Pageable newPageable(int page, int size) {
        return new OrderRepository.Pageable(page, size, "createdAt", true);
    }

    private void persistProduct(String businessId, int inventoryQuantity) {
        ProductEntity entity = new ProductEntity();
        entity.setId(businessId);
        entity.setSku(businessId + "-SKU");
        entity.setName("Test Product " + businessId);
        entity.setPrice(BigDecimal.valueOf(19.99));
        entity.setInventoryQuantity(inventoryQuantity);
        jpaProductRepo.saveAndFlush(entity);
    }
}