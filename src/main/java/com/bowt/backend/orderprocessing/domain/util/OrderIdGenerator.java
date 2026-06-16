package com.bowt.backend.orderprocessing.domain.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/** Generates UUIDv7 — time-ordered, safe for B-tree indexes. Thread-safe. */
public final class OrderIdGenerator {

    private static final TimeBasedEpochGenerator GENERATOR =
            Generators.timeBasedEpochGenerator();

    private OrderIdGenerator() {}

    public static UUID generate() {
        return GENERATOR.generate();
    }
}
