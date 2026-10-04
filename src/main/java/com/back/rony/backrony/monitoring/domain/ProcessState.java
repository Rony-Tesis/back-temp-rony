package com.back.rony.backrony.monitoring.domain;

import java.util.List;

/** Six-stage wire contract shared with the existing Angular application. */
public record ProcessState(
        int stageIndex,
        long elapsedMs,
        long stageElapsedMs,
        List<Long> stageDurationsMs,
        double progressPercent,
        boolean completed,
        String lastResult,
        boolean gripperClosed) {
    public ProcessState {
        stageDurationsMs = List.copyOf(stageDurationsMs);
        if (stageIndex < 0
                || stageIndex > 5
                || elapsedMs < 0
                || elapsedMs > 86400000
                || stageElapsedMs < 0
                || stageElapsedMs > 86400000
                || stageDurationsMs.size() != 6
                || stageDurationsMs.stream().anyMatch(v -> v < 0 || v > 86400000)
                || stageElapsedMs > stageDurationsMs.get(stageIndex)
                || !Double.isFinite(progressPercent)
                || progressPercent < 0
                || progressPercent > 100
                || (completed && (progressPercent != 100 || gripperClosed || lastResult == null))) {
            throw new IllegalArgumentException("Estado del proceso incoherente.");
        }
    }
}
