package com.back.rony.backrony.history.domain;

import java.time.Instant;

/** Classification result; a physical grasp alone must never produce one. */
public record Operation(
        String id,
        Instant completedAt,
        String residue,
        String category,
        double confidence,
        String result,
        long durationMs,
        String destination,
        Boolean depositConfirmed) {
    public Operation(
            String id,
            Instant completedAt,
            String residue,
            String category,
            double confidence,
            String result,
            long durationMs) {
        this(id, completedAt, residue, category, confidence, result, durationMs, null, null);
    }

    public Operation {
        completedAt = completedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
}
