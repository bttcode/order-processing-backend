package com.bowt.backend.orderprocessing.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Usage pattern (see {@code IdempotencyHandler} in tree-1.md): the REST layer
 * checks {@link #find} before invoking the use case. On a hit, it replays the
 * cached response as {@code 200 OK} (never {@code 201 Created} — see [OI-18]).
 * On a miss, it invokes the use case and calls {@link #save}. On a hit with a
 * different {@code operationHash}, the caller returns {@code 409 Conflict}.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> find(UUID key);

    /**
     * @param operationHash hash of the normalized request payload — used to
     *                      detect [OI-3]/API-2's "same key, different
     *                      payload" conflict case (409).
     */
    void save(UUID key, String operationHash, String responseBody, int status);

    record StoredResponse(String operationHash, String responseBody, int status) {
    }
}