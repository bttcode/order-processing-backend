package com.bowt.backend.orderprocessing.infrastructure.persistence.scheduled;

import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaIdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * [OI-6] The idempotency_keys table has an expires_at TTL column but nothing was purging
 * expired rows — without this job the table grows unboundedly. Runs hourly by default;
 * override with `idempotency.cleanup.cron` in application.yml. Uses idx_idempotency_expires
 * (see 04-database.md DB-1) so the DELETE ... WHERE expires_at < ? is an index scan, not a
 * sequential scan, even once the table has millions of historical rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupJob {

    private final JpaIdempotencyKeyRepository repository;

    @Scheduled(cron = "${idempotency.cleanup.cron:0 0 * * * *}")
    @Transactional
    public void purgeExpiredKeys() {
        int deleted = repository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Idempotency cleanup: purged {} expired keys", deleted);
        }
    }
}