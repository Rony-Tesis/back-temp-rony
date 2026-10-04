package com.back.rony.backrony.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HistoryPersistenceTest {
    @Autowired HistoryPort history;

    @Test
    void physicalGraspsNeverInflateClassificationSummary() {
        long classifications = history.summary(Instant.EPOCH).total();
        long grasps = history.countGrasps();
        history.saveGrasp(Detection.demo(0));
        assertEquals(grasps + 1, history.countGrasps());
        assertEquals(classifications, history.summary(Instant.EPOCH).total());
    }

    @Test
    void operationRoundTripPreservesContractAndAggregates() {
        long previous = history.summary(Instant.EPOCH).total();
        String id = UUID.randomUUID().toString();
        Operation operation =
                new Operation(id, Instant.now(), "Plástico", "plastic", .91, "success", 15900);
        history.saveOperation(operation, "simulation");
        assertEquals(previous + 1, history.summary(Instant.EPOCH).total());
        assertTrue(
                history.recentOperations().stream()
                        .anyMatch(o -> id.equals(o.id()) && o.confidence() == .91));
    }
}
