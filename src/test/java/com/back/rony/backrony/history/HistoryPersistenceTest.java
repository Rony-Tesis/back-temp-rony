package com.back.rony.backrony.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
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
    void unverifiedPhysicalCyclePersistsDestinationWithoutInventingSuccess() {
        long successes = history.summary(Instant.EPOCH).successful();
        long unverified = history.summary(Instant.EPOCH).unverified();
        Operation operation =
                new Operation(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "Cartón",
                        "cardboard",
                        .91,
                        "unverified",
                        24000,
                        "Cartón · destino provisional",
                        null);
        history.saveOperation(operation, "live");
        assertEquals(unverified + 1, history.summary(Instant.EPOCH).unverified());
        assertEquals(successes, history.summary(Instant.EPOCH).successful());
        assertTrue(
                history.recentOperations().stream()
                        .anyMatch(
                                o ->
                                        o.id().equals(operation.id())
                                                && operation.destination().equals(o.destination())
                                                && o.depositConfirmed() == null));
    }

    @Test
    void remoteOperationRetriesRemainSingleAfterDatabaseRoundTrip() {
        long before = history.summary(Instant.EPOCH).total();
        var event =
                new Operation(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "Plástico",
                        "plastic",
                        .95,
                        "unverified",
                        45000,
                        "Plástico · destino provisional",
                        null);
        history.saveRemoteOperation(event);
        history.saveRemoteOperation(event);
        assertEquals(before + 1, history.summary(Instant.EPOCH).total());
        assertEquals(
                1,
                history.recentOperations().stream().filter(o -> o.id().equals(event.id())).count());
    }

    @Test
    void currentGripperAndAllMaterialsAreAcceptedByMigratedSchema() {
        long before = history.countGrasps();
        for (String category : java.util.List.of("plastic", "aluminum", "cardboard")) {
            history.saveGrasp(TestProfile.target(category));
        }
        assertEquals(before + 3, history.countGrasps());
    }

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
