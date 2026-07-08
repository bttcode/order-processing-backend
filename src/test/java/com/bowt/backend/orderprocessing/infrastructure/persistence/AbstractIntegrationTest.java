package com.bowt.backend.orderprocessing.infrastructure.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers PostgreSQL 15 base class for every {@code infrastructure/persistence}
 * integration test (06-testing.md § TEST-2). H2 is explicitly disallowed for this layer —
 * behaviour we care about here (optimistic locking via {@code @Version}, the
 * {@code REPEATABLE_READ} isolation level, composite index selection) does not reproduce
 * faithfully on H2.
 *
 * <h2>Singleton container pattern</h2>
 * The container is started once, in a static initializer, and is deliberately <b>not</b>
 * annotated {@code @Container} — that would hand lifecycle control to JUnit and cause a fresh
 * container (and a fresh Flyway migration run) per test class. At 5+ integration test classes
 * that is 5x the boot cost for no correctness benefit, since none of these tests depend on
 * cross-class isolation at the container level (each test gets row-level isolation via
 * {@link Transactional} rollback instead — see below). Testcontainers' Ryuk reaper container
 * kills PostgreSQL when the JVM exits; there is no explicit {@code stop()} call.
 *
 * <h2>Transactional rollback strategy</h2>
 * Every test method here runs inside a Spring-managed transaction that is rolled back after
 * the test completes (06-testing.md § TEST-2 rule: "@Transactional + rollback, or @Sql clean-up
 * scripts" — this class picks the former). This is safe for the adapters under test because
 * none of them declare their own transaction boundaries beyond what the calling service layer
 * would provide in production (see {@code ProductJpaAdapter}'s javadoc: it relies on the
 * caller's transaction for the version-check-on-flush semantics). If an adapter method is later
 * changed to use {@code REQUIRES_NEW} internally, tests exercising that path must open their
 * own nested transaction explicitly rather than relying on this class's rollback — a single
 * outer rollback will not undo a REQUIRES_NEW commit.
 *
 * <h2>Assumptions flagged for verification</h2>
 * <ul>
 *   <li>Flyway migration {@code V1__initial_schema.sql} (ADR-001 revised DDL) is on the
 *       classpath at {@code src/main/resources/db/migration/} and runs automatically against
 *       the container on context startup.</li>
 *   <li>A {@code test} Spring profile exists (referenced by {@code tree-test.md} as
 *       {@code application-test.yml}) and does not hard-code a datasource URL that would
 *       conflict with the properties registered below.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                .withDatabaseName("order_processing_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true); // safe under Ryuk; speeds up iterative local runs
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Schema-under-test must be the real Flyway migration, not Hibernate ddl-auto —
        // otherwise a passing test proves nothing about the production schema.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Enables Hibernate Statistics (query counts) for the N+1 regression test in
        // OrderJpaAdapterIntegrationTest. Negligible overhead at this data volume.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }
}