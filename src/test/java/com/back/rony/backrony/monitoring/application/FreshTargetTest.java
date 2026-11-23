package com.back.rony.backrony.monitoring.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FreshTargetTest {
    @Test
    void aPreviouslyStableObservationCannotReplaceEightFreshFrames() {
        VisionPort source =
                new VisionPort() {
                    public void start(Consumer<Observation> observed, Consumer<String> failed) {}

                    public void close() {}
                };
        HistoryPort history =
                new HistoryPort() {
                    public void saveOperation(Operation operation, String mode) {}

                    public List<Operation> recentOperations() {
                        return List.of();
                    }

                    public Summary summary(Instant since) {
                        return new Summary(0, 0, 0, 0, 0);
                    }

                    public long countGrasps() {
                        return 0;
                    }

                    public void saveGrasp(Detection target) {}
                };
        try (var monitor =
                new MonitorService(source, "live", history, null, TestProfile.profile())) {
            var target = TestProfile.target("plastic");
            for (int i = 0; i < 7; i++)
                monitor.observed(new VisionPort.Observation(new byte[] {1}, target, 100, target));
            assertFalse(monitor.snapshot().simulationState().targetReady());
            monitor.observed(new VisionPort.Observation(new byte[] {1}, target, 100, target));
            assertTrue(monitor.snapshot().simulationState().targetReady());
            assertTrue(monitor.snapshot().simulationState().stableFrames() == 8);
        }
    }
}
