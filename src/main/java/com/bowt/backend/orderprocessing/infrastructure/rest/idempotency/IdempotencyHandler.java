package com.bowt.backend.orderprocessing.infrastructure.rest.idempotency;

import com.bowt.backend.orderprocessing.application.port.out.IdempotencyStore;
import com.bowt.backend.orderprocessing.application.port.out.IdempotencyStore.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * FR-8 / [OI-18 fix]. Centralizes the "check store BEFORE calling the use-case" logic that
 * both v1 and v2 controllers need for POST /orders and POST /orders/{id}/cancel. This is
 * exactly the fix OI-18 calls for: the controller (via this handler) inspects the store
 * first, and explicitly returns 200 OK on a cache hit rather than letting the use-case's
 * normal 201 Created path run unconditionally.
 * <p>
 * Conflict detection (409): the incoming request payload is hashed (SHA-256) and compared
 * against the hash stored with the original response. Same key + same hash = replay (200).
 * Same key + different hash = conflict (409) per FR-8's acceptance criteria.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyHandler {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public <T> ResponseEntity<?> handle(UUID idempotencyKey,
                                        Object requestPayload,
                                        Supplier<ResponseEntity<T>> operation,
                                        Class<T> responseType) {
        String incomingHash = hash(requestPayload);
        Optional<StoredResponse> existing = store.find(idempotencyKey);

        if (existing.isPresent()) {
            StoredResponse stored = existing.get();
            if (!stored.operationHash().equals(incomingHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload");
            }
            // Replay: same key, same payload -> return the cached response with 200, NOT the
            // original 201. This is the exact behaviour OI-18 requires the controller to enforce.
            T cachedBody = deserialize(stored.responseBody(), responseType);
            return ResponseEntity.status(HttpStatus.OK).body(cachedBody);
        }

        ResponseEntity<T> result = operation.get();
        store.save(idempotencyKey, incomingHash, serialize(result.getBody()), result.getStatusCode().value());
        return result;
    }

    private String hash(Object payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash idempotency payload", e);
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response for idempotency store", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }
}