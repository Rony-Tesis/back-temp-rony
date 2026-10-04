package com.back.rony.backrony.monitoring.domain;

import com.back.rony.backrony.history.domain.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pure virtual state machine, with no hardware, HTTP, or persistence dependencies. */
public final class VirtualCycle {
    public static final List<Long> DURATIONS = List.of(1800L, 1400L, 2200L, 3000L, 5500L, 2000L);
    private static final long TOTAL = DURATIONS.stream().mapToLong(Long::longValue).sum();
    private long elapsed;
    private boolean active;
    private boolean paused;
    private boolean completed;
    private Detection detection;

    public void start(Detection target) {
        if (active) throw new IllegalStateException("Ya hay un ciclo virtual en ejecución.");
        detection = target;
        elapsed = 0;
        active = true;
        completed = false;
    }

    public Operation advance(long millis, Instant now) {
        if (!active || paused) return null;
        elapsed = Math.min(TOTAL, elapsed + Math.max(0, millis));
        if (elapsed < TOTAL) return null;
        active = false;
        completed = true;
        return new Operation(
                UUID.randomUUID().toString(),
                now,
                detection.residue(),
                detection.category(),
                detection.confidence(),
                "success",
                TOTAL);
    }

    public ProcessState process() {
        long remaining = elapsed;
        int stage = 0;
        while (stage < 5 && remaining >= DURATIONS.get(stage)) remaining -= DURATIONS.get(stage++);
        return new ProcessState(
                stage,
                elapsed,
                Math.min(remaining, DURATIONS.get(stage)),
                DURATIONS,
                Math.round(elapsed * 10000.0 / TOTAL) / 100.0,
                completed,
                completed ? "success" : null,
                active && (stage == 3 || stage == 4));
    }

    public boolean active() {
        return active;
    }

    public boolean paused() {
        return paused;
    }

    public Detection detection() {
        return detection;
    }

    public void paused(boolean value) {
        paused = value;
    }
}
