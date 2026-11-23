package com.back.rony.backrony.robotics.infrastructure;

import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.MotionPlan.Step;
import com.back.rony.backrony.robotics.domain.RobotPose;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated LAN/VPN transport; complete trajectories execute inside JetMax, without SSH. */
public final class HttpRobotAdapter implements RobotPort {
    private final URI endpoint;
    private final String token;
    private final RobotProfile profile;
    private final ObjectMapper mapper;
    private final boolean allowTestAgent;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private volatile JsonNode state;
    private volatile RobotPose pose;
    private volatile long poseAt;
    private volatile String bootId;
    private volatile boolean closed;
    private Consumer<RobotPose> telemetry = p -> {};

    public HttpRobotAdapter(URI endpoint, String token, RobotProfile profile, ObjectMapper mapper) {
        this(endpoint, token, profile, mapper, false);
    }

    public HttpRobotAdapter(
            URI endpoint,
            String token,
            RobotProfile profile,
            ObjectMapper mapper,
            boolean allowTestAgent) {
        this.endpoint = endpoint;
        this.token = token;
        this.profile = profile;
        this.mapper = mapper;
        this.allowTestAgent = allowTestAgent;
    }

    private JsonNode request(String path, Object payload) {
        if (closed || token == null || token.length() < 32) {
            throw new IllegalStateException("Configura el token del agente del JetMax.");
        }
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(endpoint.resolve(path))
                            .timeout(Duration.ofSeconds(4))
                            .header("Authorization", "Bearer " + token);
            if (payload == null) builder.GET();
            else
                builder.header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(payload)));
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String detail = "";
                try {
                    JsonNode reason = mapper.readTree(response.body()).path("detail");
                    if (reason.isString()) {
                        detail = reason.asString().replaceAll("[\\p{Cntrl}]", " ").strip();
                        detail = detail.substring(0, Math.min(160, detail.length()));
                    }
                } catch (RuntimeException ignored) {
                    // Unstructured proxy responses must not enter the public status.
                }
                throw new IllegalStateException(
                        "Agente JetMax (HTTP "
                                + response.statusCode()
                                + ")"
                                + (detail.isEmpty() ? "." : ": " + detail));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "No hay conexión con el agente JetMax; revisa Wi-Fi, IP y servicio.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private synchronized JsonNode refresh() {
        JsonNode next = request("/v1/state", null);
        if (next.path("schemaVersion").asInt() != 2) {
            throw new IllegalStateException(
                    "Actualiza nuestro agente del JetMax para leer la calibración del brazo.");
        }
        if (!"vertical-home-before-disposal-v1".equals(next.path("motionPolicy").asString())) {
            throw new IllegalStateException(
                    "Actualiza el agente: se requiere elevación vertical y HOME antes de descargar.");
        }
        if (next.path("testMode").asBoolean() && !allowTestAgent) {
            throw new IllegalStateException(
                    "El agente usa un robot falso; el modo físico lo rechaza.");
        }
        if (!profile.sha256().equals(next.path("profileSha256").asString())) {
            throw new IllegalStateException("Sincroniza el perfil del notebook entre PC y JetMax.");
        }
        String nextBoot = next.path("bootId").asString();
        if (bootId != null && !bootId.equals(nextBoot)) {
            throw new IllegalStateException("El agente se reinició; se requiere reset explícito.");
        }
        bootId = nextBoot;
        if (!next.path("connected").asBoolean() || next.path("pose").isNull()) {
            throw new IllegalStateException("Telemetría ROS del agente no disponible.");
        }
        if (!next.path("calibrationSha256").asString("").matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("La calibración del brazo no está disponible.");
        }
        pose = mapper.treeToValue(next.path("pose"), RobotPose.class);
        poseAt = System.nanoTime();
        state = next;
        telemetry.accept(pose);
        return next;
    }

    @Override
    public void connect(Consumer<RobotPose> telemetry) {
        this.telemetry = telemetry;
        refresh();
        poller.scheduleWithFixedDelay(
                () -> {
                    try {
                        refresh();
                    } catch (RuntimeException ignored) {
                        poseAt = 0;
                    }
                },
                100,
                100,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public RobotPose currentPose() {
        RobotPose current = pose;
        if (closed || current == null || System.nanoTime() - poseAt > TimeUnit.SECONDS.toNanos(2)) {
            throw new IllegalStateException("Se perdió la telemetría del JetMax.");
        }
        return current;
    }

    @Override
    public String calibrationSha256() {
        currentPose();
        return state.path("calibrationSha256").asString();
    }

    /** Read-only snapshot for vision; the notebook never overwrites ROS calibration. */
    public JsonNode readCalibration() {
        JsonNode packet = request("/v1/calibration", null);
        if (packet.path("schemaVersion").asInt() != 2
                || !"vertical-home-before-disposal-v1"
                        .equals(packet.path("motionPolicy").asString())
                || !profile.sha256().equals(packet.path("profileSha256").asString())
                || (packet.path("testMode").asBoolean() && !allowTestAgent)
                || !packet.path("sha256").asString("").matches("[0-9a-f]{64}")
                || !packet.path("parameters").isObject()) {
            throw new IllegalStateException(
                    "Calibración del agente incompatible; actualiza el agente.");
        }
        return packet;
    }

    @Override
    public void preflight(List<RobotPose.Position> positions) {
        request(
                "/v1/preflight",
                Map.of(
                        "positions",
                        positions.stream().map(p -> List.of(p.x(), p.y(), p.z())).toList(),
                        "profileSha256",
                        profile.sha256(),
                        "calibrationSha256",
                        calibrationSha256()));
    }

    @Override
    public RobotPose execute(Step step, RobotPose expected) {
        return executePlan(List.of(step), expected, () -> {}, s -> {}, s -> {});
    }

    @Override
    public RobotPose executePlan(
            List<Step> steps,
            RobotPose expected,
            Runnable guard,
            Consumer<Step> started,
            Consumer<Step> completed) {
        return executeOperation(steps, expected, guard, started, completed, null);
    }

    @Override
    public RobotPose executeManual(
            String action,
            RobotPose expected,
            RobotProfile profile,
            Runnable guard,
            Consumer<Step> started,
            Consumer<Step> completed) {
        if (state.path("manualControlsVersion").asInt() != 1) {
            throw new IllegalStateException(
                    "Actualiza el agente del JetMax para usar los controles manuales.");
        }
        return executeOperation(
                ManualControls.steps(action, expected, profile),
                expected,
                guard,
                started,
                completed,
                action);
    }

    private RobotPose executeOperation(
            List<Step> steps,
            RobotPose expected,
            Runnable guard,
            Consumer<Step> started,
            Consumer<Step> completed,
            String manualAction) {
        guard.run();
        String planCalibration = calibrationSha256();
        String id = UUID.randomUUID().toString();
        var wireSteps = new ArrayList<Map<String, Object>>();
        for (Step step : steps) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("stage", step.stage());
            wire.put("label", step.label());
            wire.put("duration", step.duration());
            if (step.position() != null)
                wire.put(
                        "position",
                        List.of(step.position().x(), step.position().y(), step.position().z()));
            else wire.put("gripper", step.gripper());
            wireSteps.add(wire);
        }
        // Never retry an ambiguous POST: the agent may already be moving.
        Map<String, Object> body =
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                id,
                                "steps",
                                wireSteps,
                                "profileSha256",
                                profile.sha256(),
                                "calibrationSha256",
                                planCalibration,
                                "expected",
                                Map.of(
                                        "x",
                                        expected.x(),
                                        "y",
                                        expected.y(),
                                        "z",
                                        expected.z(),
                                        "gripperAngle",
                                        expected.gripperAngle())));
        if (manualAction != null) {
            body.remove("steps");
            body.put("action", manualAction);
        }
        request(manualAction == null ? "/v1/plans" : "/v1/manual", body);
        int announced = -1, finished = 0;
        RuntimeException stopping = null;
        long stopAt = 0;
        while (!closed) {
            if (stopping == null) {
                try {
                    guard.run();
                } catch (RuntimeException stop) {
                    stopping = stop;
                    stopAt = System.nanoTime();
                    request("/v1/cancel", Map.of("id", id));
                }
            }
            if (stopping == null) request("/v1/heartbeat", Map.of("id", id));
            JsonNode next = refresh();
            JsonNode job = next.path("job");
            if (!id.equals(job.path("id").asString())) {
                throw new IllegalStateException(
                        "El agente perdió la trayectoria; revisa y resetea.");
            }
            int index = job.path("index").asInt();
            if (index >= 0 && index < steps.size() && index != announced) {
                started.accept(steps.get(index));
                announced = index;
            }
            int done = job.path("completedSteps").asInt();
            while (finished < Math.min(done, steps.size())) completed.accept(steps.get(finished++));
            String status = job.path("state").asString();
            if (!"running".equals(status)) {
                if (stopping != null) throw stopping;
                if ("completed".equals(status) && done == steps.size()) return currentPose();
                if ("cancelled".equals(status))
                    throw new CancellationException("Agente cancelado por pérdida de control.");
                throw new IllegalStateException(
                        "Trayectoria detenida en el JetMax: " + job.path("error").asString());
            }
            if (stopping != null && System.nanoTime() - stopAt > TimeUnit.SECONDS.toNanos(15)) {
                throw new IllegalStateException(
                        "Espera que el agente termine el tramo antes de resetear.");
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
        }
        throw new CancellationException();
    }

    @Override
    public void close() {
        closed = true;
        poller.shutdownNow();
        http.close();
    }
}
