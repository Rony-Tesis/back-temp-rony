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
        long durationMs) {
    public Operation {
        completedAt = completedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
}
