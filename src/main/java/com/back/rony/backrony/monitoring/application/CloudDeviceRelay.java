package com.back.rony.backrony.monitoring.application;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.ProcessState;
import com.back.rony.backrony.robotics.application.PhysicalMotionService.RobotState;
import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.RobotPose;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Cloud receives robot-owned inference/results. It never computes trajectories or runs Python. */
public final class CloudDeviceRelay implements MonitorPort {
    public record Exchange(
            String deviceId,
            String bootId,
            long sequence,
            String profileSha256,
            String motionPolicy,
            RobotState robotState,
            Detection detection,
            Detection targetDetection,
            ProcessState process,
            boolean cameraOnline,
            int stableFrames,
            int requiredStableFrames,
            double framesPerSecond,
            String jpeg,
            List<Operation> operations,
            List<String> acknowledgedCommands) {}

    public record DeviceCommand(String id, String action, String bootId, int validForSeconds) {}

    public record Reply(
            List<DeviceCommand> commands,
            List<String> acknowledgedOperations,
            int pollIntervalMs,
            int controlLeaseSeconds) {}

    private record Pending(DeviceCommand command, long created) {}

    private static final ProcessState EMPTY =
            new ProcessState(0, 0, 0, List.of(0L, 0L, 0L, 0L, 0L, 0L), 0, false, null, false);
    private final HistoryPort history;
    private final RobotProfile profile;
    private final String deviceId;
    private final boolean browserStreaming;
    private final DeviceCamera camera = new DeviceCamera();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Set<String> retiredBoots = new LinkedHashSet<>();
    private HistoryPort.Summary cachedSummary;
    private List<Operation> cachedOperations = List.of();
    private long historyAt;
    private Exchange latest;
    private long received;
    private Instant receivedAt;
    private boolean running;

    public CloudDeviceRelay(HistoryPort history, RobotProfile profile, String deviceId) {
        this(history, profile, deviceId, true);
    }

    public CloudDeviceRelay(
            HistoryPort history, RobotProfile profile, String deviceId, boolean browserStreaming) {
        this.history = history;
        this.profile = profile;
        this.deviceId = deviceId;
        this.browserStreaming = browserStreaming;
    }

    public synchronized Reply exchange(Exchange packet) {
        validate(packet);
        for (Operation operation : packet.operations()) validateOperation(operation);
        byte[] next = packet.jpeg() == null ? null : Base64.getDecoder().decode(packet.jpeg());
        if (next != null) DeviceCamera.validate(next);
        if (retiredBoots.contains(packet.bootId()))
            throw new IllegalArgumentException("Sesión anterior del JetMax.");
        if (latest != null
                && latest.bootId().equals(packet.bootId())
                && packet.sequence() < latest.sequence()) {
            throw new IllegalArgumentException("Telemetría fuera de orden.");
        }
        if (latest == null || !latest.bootId().equals(packet.bootId())) {
            if (latest != null) retiredBoots.add(latest.bootId());
            while (retiredBoots.size() > 64) retiredBoots.remove(retiredBoots.iterator().next());
            pending.clear();
        }
        packet.acknowledgedCommands().forEach(pending::remove);
        List<String> acknowledged = new ArrayList<>();
        for (Operation operation : packet.operations()) {
            // The robot RTC may differ; use receipt time for remote history, stable ID for retries.
            history.saveRemoteOperation(
                    new Operation(
                            operation.id(),
                            Instant.now(),
                            operation.residue(),
                            operation.category(),
                            operation.confidence(),
                            operation.result(),
                            operation.durationMs(),
                            operation.destination(),
                            null));
            acknowledged.add(operation.id());
        }
        latest = packet;
        if (!acknowledged.isEmpty()) historyAt = 0;
        camera.telemetry(packet.bootId(), packet.cameraOnline(), next);
        received = System.nanoTime();
        receivedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        pending.values().removeIf(item -> received - item.created() > TimeUnit.SECONDS.toNanos(10));
        notifyAll();
        return new Reply(
                pending.values().stream().map(Pending::command).toList(), acknowledged, 200, 15);
    }

    private void validate(Exchange packet) {
        if (packet == null
                || !deviceId.equals(packet.deviceId())
                || packet.bootId() == null
                || !UUID.fromString(packet.bootId()).toString().equals(packet.bootId())
                || packet.sequence() < 0
                || !profile.sha256().equals(packet.profileSha256())
                || !"vertical-home-before-disposal-v1".equals(packet.motionPolicy())
                || packet.robotState() == null
                || packet.process() == null
                || packet.stableFrames() < 0
                || packet.stableFrames() > 100000
                || packet.requiredStableFrames() != profile.integer("STABLE_FRAMES")
                || !Double.isFinite(packet.framesPerSecond())
                || packet.framesPerSecond() < 0
                || packet.framesPerSecond() > 240
                || packet.operations() == null
                || packet.operations().size() > 16
                || packet.acknowledgedCommands() == null
                || packet.acknowledgedCommands().size() > 32
                || (packet.jpeg() != null && packet.jpeg().length() > 400000)) {
            throw new IllegalArgumentException(
                    "Paquete del JetMax inválido o perfil desincronizado.");
        }
        RobotState robot = packet.robotState();
        if (!List.of(
                                "idle",
                                "preparing",
                                "waiting",
                                "moving",
                                "resetting",
                                "cancelled",
                                "fault",
                                "manual",
                                "parked",
                                "finished")
                        .contains(robot.state())
                || robot.stepLabel() == null
                || robot.stepLabel().length() > 200
                || (robot.error() != null && robot.error().length() > 200)
                || robot.completedCycles() < 0
                || robot.completedGrasps() < 0
                || (robot.gripperAngle() != null
                        && (!Double.isFinite(robot.gripperAngle())
                                || robot.gripperAngle() < 0
                                || robot.gripperAngle() > 180))) {
            throw new IllegalArgumentException("Estado del JetMax inválido.");
        }
        if (robot.reportedPosition() != null) {
            var p = robot.reportedPosition();
            if (!Double.isFinite(p.x())
                    || !Double.isFinite(p.y())
                    || !Double.isFinite(p.z())
                    || Math.abs(p.x()) > 1000
                    || Math.abs(p.y()) > 1000
                    || Math.abs(p.z()) > 1000) {
                throw new IllegalArgumentException("Posición reportada inválida.");
            }
        }
    }

    private void validateOperation(Operation operation) {
        if (operation == null
                || operation.id() == null
                || !UUID.fromString(operation.id()).toString().equals(operation.id())
                || operation.residue() == null
                || operation.residue().length() > 160
                || !profile.enabledCategories().contains(operation.category())
                || !Double.isFinite(operation.confidence())
                || operation.confidence() < .8
                || operation.confidence() > 1
                || !List.of("unverified", "failure").contains(operation.result())
                || operation.durationMs() < 0
                || operation.durationMs() > 86400000
                || operation.destination() == null
                || operation.destination().length() > 160
                || operation.depositConfirmed() != null) {
            throw new IllegalArgumentException("Resultado físico inválido.");
        }
    }

    private boolean fresh() {
        return latest != null && System.nanoTime() - received < TimeUnit.SECONDS.toNanos(6);
    }

    private RobotState robot() {
        if (!fresh())
            return new RobotState(
                    false,
                    "fault",
                    "Sin comunicación con el JetMax",
                    false,
                    false,
                    null,
                    latest == null ? 0 : latest.robotState().completedGrasps(),
                    null,
                    null,
                    null,
                    null,
                    "El JetMax debe conectarse por HTTPS a esta API.",
                    latest == null ? 0 : latest.robotState().completedCycles(),
                    true);
        RobotState robot = latest.robotState();
        return new RobotState(
                robot.connected(),
                robot.state(),
                robot.stepLabel(),
                robot.autoEnabled(),
                robot.cycleActive(),
                null,
                robot.completedGrasps(),
                robot.reportedPosition(),
                robot.gripperAngle(),
                robot.reportedJoints(),
                receivedAt,
                robot.error(),
                robot.completedCycles(),
                true);
    }

    public synchronized void start() {
        running = true;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized MonitorSnapshot snapshot() {
        if (cachedSummary == null || System.nanoTime() - historyAt > TimeUnit.SECONDS.toNanos(5)) {
            cachedSummary =
                    history.summary(
                            LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());
            cachedOperations = history.recentOperations();
            historyAt = System.nanoTime();
        }
        boolean online = fresh();
        RobotState robot = robot();
        return new MonitorSnapshot(
                "baseline",
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                "live",
                "physical",
                browserStreaming ? "sse" : "polling",
                robot,
                browserStreaming ? "mjpeg" : "jpeg",
                new MonitorSnapshot.SimulationState(
                        false,
                        robot.cycleActive(),
                        online ? latest.stableFrames() : 0,
                        profile.integer("STABLE_FRAMES"),
                        online && latest.targetDetection() != null),
                online ? latest.detection() : null,
                online ? latest.targetDetection() : null,
                online ? latest.process() : EMPTY,
                cachedSummary,
                cachedOperations,
                List.of("camera", "yolo", "robot", "gripper", "planner").stream()
                        .map(
                                id ->
                                        new MonitorSnapshot.Equipment(
                                                id,
                                                (List.of("camera", "yolo").contains(id)
                                                                ? online && latest.cameraOnline()
                                                                : robot.connected())
                                                        ? "online"
                                                        : "offline"))
                        .toList(),
                online ? latest.framesPerSecond() : 0);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceMode", "live");
        value.put("motionMode", "physical");
        value.put("executionLocation", "jetmax");
        value.put("physicalMotionEnabled", true);
        value.put("robotState", robot());
        value.put("cameraOnline", fresh() && latest.cameraOnline());
        value.put("autoCycle", fresh() && latest.robotState().autoEnabled());
        value.put("cycleActive", fresh() && latest.robotState().cycleActive());
        value.put("stableFrames", fresh() ? latest.stableFrames() : 0);
        value.put("requiredStableFrames", profile.integer("STABLE_FRAMES"));
        value.put("provisionalDestinations", true);
        value.put("destinations", profile.bins());
        value.put("frameSequence", camera.frame().sequence());
        value.put("videoTransport", "wss-jpeg-v1");
        value.put("paused", false);
        value.put("error", null);
        return value;
    }

    public Frame frame() {
        return camera.frame();
    }

    public Frame waitFrame(long after) throws InterruptedException {
        return camera.waitFrame(after);
    }

    public boolean openVideo(String boot, String channel) {
        return camera.open(boot, channel);
    }

    public boolean videoFrame(String boot, String channel, long sourceSequence, byte[] jpeg) {
        return camera.receive(boot, channel, sourceSequence, jpeg);
    }

    public void closeVideo(String channel) {
        camera.disconnect(channel);
    }

    public synchronized void physicalCommand(String action) {
        if (!fresh())
            throw new IllegalStateException("JetMax desconectado; no se encolan movimientos.");
        RobotState robot = latest.robotState();
        if (!List.of("arm", "cancel", "reset", "close-gripper", "park").contains(action))
            throw new IllegalArgumentException("Comando inválido.");
        if (ManualControls.isAction(action)) {
            if (!robot.connected()
                    || robot.autoEnabled()
                    || robot.cycleActive()
                    || List.of("fault", "resetting").contains(robot.state())
                    || !pending.isEmpty()) {
                throw new IllegalStateException(
                        "Detén el automático y espera antes de usar controles manuales.");
            }
            if ("park".equals(action)) {
                var pose = robot.reportedPosition();
                if (pose == null || robot.gripperAngle() == null)
                    throw new IllegalStateException("Sin posición ROS reciente.");
                ManualControls.steps(
                        action,
                        new RobotPose(pose.x(), pose.y(), pose.z(), robot.gripperAngle()),
                        profile);
            }
        }
        if ("arm".equals(action)
                && (!robot.connected()
                        || !latest.cameraOnline()
                        || !List.of("idle", "finished").contains(robot.state())
                        || !pending.isEmpty())) {
            throw new IllegalStateException("El JetMax aún no está listo para iniciar.");
        }
        if ("reset".equals(action) && (robot.cycleActive() || !pending.isEmpty())) {
            throw new IllegalStateException("Cancela y espera el tramo antes de resetear.");
        }
        if ("cancel".equals(action)) pending.clear();
        var command = new DeviceCommand(UUID.randomUUID().toString(), action, latest.bootId(), 10);
        pending.put(command.id(), new Pending(command, System.nanoTime()));
    }

    public void virtualCommand(String action) {
        throw new IllegalStateException("El JetMax ejecuta el ciclo real.");
    }

    public synchronized void close() {
        running = false;
        camera.close();
        notifyAll();
    }
}
