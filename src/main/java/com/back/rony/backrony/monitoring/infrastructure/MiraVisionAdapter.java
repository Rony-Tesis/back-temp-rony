package com.back.rony.backrony.monitoring.infrastructure;

import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.shared.infrastructure.PythonWorkerProcess;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Retains best.pt inference without importing or running the old FastAPI project. */
public final class MiraVisionAdapter implements VisionPort {
    private final PythonWorkerProcess worker;
    private final ObjectMapper mapper;
    private final Supplier<JsonNode> calibrationReader;
    private final Runnable closeCalibrationReader;
    private final ScheduledExecutorService calibrationPoller =
            Executors.newSingleThreadScheduledExecutor();
    private String calibrationSha;

    public MiraVisionAdapter(
            RonyProperties properties,
            ObjectMapper mapper,
            Supplier<JsonNode> calibrationReader,
            Runnable closeCalibrationReader) {
        this.worker = new PythonWorkerProcess(properties, mapper);
        this.mapper = mapper;
        this.calibrationReader = calibrationReader;
        this.closeCalibrationReader = closeCalibrationReader;
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
                                            target,
                                            value.path("visibleCount").asInt(),
                                            value.path("calibrationSha256").asString(null)));
                        } catch (RuntimeException invalid) {
                            failed.accept("Observación inválida.");
                        }
                    },
                    () -> failed.accept("Adaptador de visión desconectado."));
            calibrationPoller.scheduleWithFixedDelay(
                    this::refreshCalibration, 0, 1, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            failed.accept("No se pudo iniciar MIRA.");
        }
    }

    private void refreshCalibration() {
        try {
            JsonNode packet = calibrationReader.get();
            String sha = packet.path("sha256").asString();
            if (!sha.equals(calibrationSha)) {
                worker.send(Map.of("action", "set-calibration", "packet", packet));
                calibrationSha = sha;
            }
        } catch (RuntimeException unavailable) {
            if (calibrationSha != null) {
                calibrationSha = null;
                worker.send(Map.of("action", "clear-calibration"));
            }
        }
    }

    @Override
    public void resetTracking() {
        worker.send(java.util.Map.of("action", "reset-tracking"));
    }

    @Override
    public void deferTarget(Detection target) {
        worker.send(
                Map.of(
                        "action",
                        "defer-target",
                        "category",
                        target.category(),
                        "x",
                        target.targetMm().x(),
                        "y",
                        target.targetMm().y()));
    }

    @Override
    public void setMotionActive(boolean active) {
        worker.send(Map.of("action", "motion-active", "active", active));
    }

    @Override
    public void close() {
        calibrationPoller.shutdownNow();
        worker.close();
        closeCalibrationReader.run();
    }
}
