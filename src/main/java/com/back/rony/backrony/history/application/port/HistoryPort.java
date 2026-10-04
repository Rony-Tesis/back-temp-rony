package com.back.rony.backrony.history.application.port;

import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.time.Instant;
import java.util.List;

public interface HistoryPort {
    void saveOperation(Operation operation, String sourceMode);

    List<Operation> recentOperations();

    Summary summary(Instant since);

    long countGrasps();

    void saveGrasp(Detection target);

    record Summary(
            long processedToday,
            long total,
            long successful,
            long failed,
            double averageDurationMs) {}
}
