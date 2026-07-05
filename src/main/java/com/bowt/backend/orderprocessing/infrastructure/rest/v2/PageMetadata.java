package com.bowt.backend.orderprocessing.infrastructure.rest.v2;

/**
 * [OI-14] v2 adds explicit page metadata to list responses; v1 omits it.
 */
public record PageMetadata(int size, int number, long totalElements, int totalPages) {
}