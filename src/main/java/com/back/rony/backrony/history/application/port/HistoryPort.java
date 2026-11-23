package com.back.rony.backrony.history.application.port;

import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.time.Instant;
import java.util.List;

public interface HistoryPort {
    void saveOperation(Operation operation, String sourceMode);

    default void saveRemoteOperation(Operation operation) {
        if (recentOperations().stream()
                .noneMatch(existing -> existing.id().equals(operation.id()))) {
            saveOperation(operation, "live");
        }
    }

    List<Operation> recentOperations();

    Summary summary(Instant since);

    long countGrasps();

    void saveGrasp(Detection target);

    default long countPhysicalCycles() {
        return 0;
    }

    record Summary(
            long processedToday,
            long total,
            long successful,
            long failed,
            double averageDurationMs,
            long unverified) {
        public Summary(
                long processedToday,
                long total,
                long successful,
                long failed,
                double averageDurationMs) {
            this(processedToday, total, successful, failed, averageDurationMs, 0);
        }
    }
}
