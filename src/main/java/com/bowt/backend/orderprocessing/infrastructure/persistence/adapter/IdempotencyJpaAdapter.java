package com.bowt.backend.orderprocessing.infrastructure.persistence.adapter;

import com.bowt.backend.orderprocessing.application.port.out.IdempotencyStore;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.IdempotencyKeyEntity;
import com.bowt.backend.orderprocessing.infrastructure.persistence.jpa.JpaIdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IdempotencyJpaAdapter implements IdempotencyStore {

    private final JpaIdempotencyKeyRepository jpaRepository;

    @Override
    public Optional<StoredResponse> find(UUID key) {
        return jpaRepository.findByKey(key)
                .filter(e -> e.getExpiresAt().isAfter(Instant.now())) // belt-and-suspenders: cleanup job may lag
                .map(e -> new StoredResponse(e.getOperationHash(), e.getResponseBody(), e.getResponseStatus()));
    }

    @Override
    @Transactional
    public void save(UUID key, String operationHash, String responseBody, int status) {
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.setKey(key);
        entity.setOperationHash(operationHash);
        entity.setResponseBody(responseBody);
        entity.setResponseStatus(status);
        jpaRepository.save(entity);
    }
}