package com.back.rony.backrony.monitoring.infrastructure;

import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.shared.infrastructure.PythonWorkerProcess;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.util.Base64;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** Retains best.pt inference without importing or running the old FastAPI project. */
public final class MiraVisionAdapter implements VisionPort {
    private final PythonWorkerProcess worker;
    private final ObjectMapper mapper;

    public MiraVisionAdapter(RonyProperties properties, ObjectMapper mapper) {
        this.worker = new PythonWorkerProcess(properties, mapper);
        this.mapper = mapper;
    }

    @Override
    public void start(Consumer<Observation> observed, Consumer<String> failed) {
        try {
            worker.start(
                    "vision",
                    value -> {
                        try {
                            if (!"vision".equals(value.path("kind").asString())) {
                                failed.accept("Cámara no disponible.");
                                return;
                            }
                            byte[] jpeg = Base64.getDecoder().decode(value.path("jpeg").asString());
                            Detection detection =
                                    value.path("detection").isNull()
                                            ? null
                                            : mapper.treeToValue(
                                                    value.path("detection"), Detection.class);
                            Detection target =
                                    value.path("targetDetection").isNull()
                                            ? null
                                            : mapper.treeToValue(
                                                    value.path("targetDetection"), Detection.class);
                            observed.accept(
                                    new Observation(
                                            jpeg,
                                            detection,
                                            value.path("stableFrames").asInt(),
                                            target));
                        } catch (RuntimeException invalid) {
                            failed.accept("Observación inválida.");
                        }
                    },
                    () -> failed.accept("Adaptador de visión desconectado."));
        } catch (RuntimeException e) {
            failed.accept("No se pudo iniciar MIRA.");
        }
    }

    @Override
    public void close() {
        worker.close();
    }
}
