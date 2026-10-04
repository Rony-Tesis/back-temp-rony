package com.back.rony.backrony.monitoring.presentation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.application.MonitorService;
import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StreamLifecycleTest {
    private MonitorService monitor() {
        VisionPort source =
                new VisionPort() {
                    public void start(Consumer<Observation> observed, Consumer<String> failed) {
                        observed.accept(new Observation(new byte[] {1, 2, 3}, null, 0, null));
                    }

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
        return new MonitorService(source, "live", history, null);
    }

    private MonitorController controller(MonitorService service) {
        var properties =
                new RonyProperties(
                        "live",
                        false,
                        false,
                        10,
                        "",
                        List.of("localhost"),
                        1,
                        Path.of("python"),
                        Path.of("worker.py"),
                        URI.create("http://127.0.0.1"),
                        Path.of("model.pt"),
                        Path.of("notebook.ipynb"));
        return new MonitorController(service, JsonMapper.builder().build(), properties);
    }

    private OutputStream closedClient() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Client disconnected");
            }
        };
    }

    @Test
    void sseDisconnectReleasesSlotWithoutAnotherErrorResponse() {
        try (var service = monitor()) {
            service.start();
            var controller = controller(service);
            var response = controller.events();
            assertEquals(429, controller.events().getStatusCode().value());
            assertDoesNotThrow(() -> response.getBody().writeTo(closedClient()));
            assertEquals(200, controller.events().getStatusCode().value());
        }
    }

    @Test
    void mjpegDisconnectReleasesSlotWithoutAnotherErrorResponse() {
        try (var service = monitor()) {
            service.start();
            var controller = controller(service);
            var response = controller.stream();
            assertEquals(429, controller.stream().getStatusCode().value());
            assertDoesNotThrow(() -> response.getBody().writeTo(closedClient()));
            assertEquals(200, controller.stream().getStatusCode().value());
        }
    }
}
