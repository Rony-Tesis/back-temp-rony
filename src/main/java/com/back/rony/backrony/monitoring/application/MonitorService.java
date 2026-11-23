package com.back.rony.backrony.monitoring.application;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import com.back.rony.backrony.monitoring.application.port.VisionPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.VirtualCycle;
import com.back.rony.backrony.robotics.application.PhysicalMotionService;
import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Coordinates use cases. Locks never nest monitor and actuator state machines. */
public final class MonitorService implements MonitorPort {
    private final VisionPort source;
    private final String sourceMode;
    private final HistoryPort history;
    private final boolean recordDemoCycles;
    private final Object lock = new Object();
    private final VirtualCycle virtual = new VirtualCycle();
    private final PhysicalMotionService physical;
    private final RobotProfile profile;
    private long targetAfterSequence, nextHistoryRefresh;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private volatile VisionPort.Observation observation;
    private volatile long frameAt;
    private volatile String error;
    private double fps;
    private long sequence, previousTick, nextCycle;
    private boolean autoCycle;
    private volatile boolean running;
    private List<Operation> operations;
    private HistoryPort.Summary summary;
    private LocalDate summaryDay;

    public MonitorService(
            VisionPort source,
            String sourceMode,
            HistoryPort history,
            Supplier<RobotPort> robotFactory,
            RobotProfile profile) {
        this(source, sourceMode, history, robotFactory, false, profile);
    }

    public MonitorService(
            VisionPort source,
            String sourceMode,
            HistoryPort history,
            Supplier<RobotPort> robotFactory,
            boolean recordDemoCycles,
            RobotProfile profile) {
        this.profile = profile;
        this.source = source;
        this.sourceMode = sourceMode;
        this.history = history;
        this.recordDemoCycles = recordDemoCycles && "simulation".equals(sourceMode);
        this.autoCycle = "simulation".equals(sourceMode) && !this.recordDemoCycles;
        this.physical =
                robotFactory == null
                        ? null
                        : new PhysicalMotionService(
                                robotFactory,
                                this::physicalScene,
                                this::resetTracking,
                                profile,
                                history,
                                source::deferTarget,
                                source::setMotionActive);
        refreshHistory();
    }

    private void refreshHistory() {
        summaryDay = LocalDate.now(ZoneOffset.UTC);
        summary = history.summary(summaryDay.atStartOfDay(ZoneOffset.UTC).toInstant());
        operations = List.copyOf(history.recentOperations());
    }

    public void start() {
        running = true;
        previousTick = System.nanoTime();
        source.start(this::observed, this::unavailable);
        if (physical != null) physical.start();
        ticker.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void observed(VisionPort.Observation value) {
        long now = System.nanoTime();
        synchronized (lock) {
            fps = frameAt == 0 ? 0 : Math.min(240, 1e9 / Math.max(1e6, now - frameAt));
            observation = value;
            frameAt = now;
            error = null;
            sequence++;
            lock.notifyAll();
        }
    }

    public void unavailable(String ignored) {
        synchronized (lock) {
            observation = null;
            frameAt = 0;
            fps = 0;
            error = "Cámara o modelo no disponible; revisa la conexión y la configuración.";
            lock.notifyAll();
        }
    }

    private void tick() {
        try {
            long now = System.nanoTime();
            synchronized (lock) {
                if (observation != null && !visionHealthy()) unavailable("");
                if (autoCycle
                        && observation != null
                        && !virtual.active()
                        && !virtual.paused()
                        && now >= nextCycle) {
                    virtual.start(Detection.demo(summary.total()));
                    previousTick = now;
                }
                Operation completed =
                        virtual.advance(
                                TimeUnit.NANOSECONDS.toMillis(now - previousTick),
                                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                previousTick = now;
                if (completed != null) {
                    if (recordDemoCycles) history.saveOperation(completed, "simulation");
                    refreshHistory();
                    nextCycle = now + TimeUnit.MILLISECONDS.toNanos(2600);
                } else if (now >= nextHistoryRefresh
                        || !LocalDate.now(ZoneOffset.UTC).equals(summaryDay)) {
                    refreshHistory();
                    nextHistoryRefresh = now + TimeUnit.SECONDS.toNanos(1);
                }
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                virtual.paused(true);
                autoCycle = false;
                error = "Proceso virtual detenido: no se pudo registrar el historial.";
            }
        }
    }

    public boolean visionHealthy() {
        return observation != null && System.nanoTime() - frameAt < TimeUnit.SECONDS.toNanos(2);
    }

    private Detection physicalTarget() {
        VisionPort.Observation value = observation;
        return visionHealthy()
                        && value != null
                        && effectiveStableFrames(value) >= profile.integer("STABLE_FRAMES")
                ? value.targetDetection()
                : null;
    }

    private int effectiveStableFrames(VisionPort.Observation value) {
        return value == null
                ? 0
                : (int)
                        Math.min(
                                profile.integer("STABLE_FRAMES"),
                                Math.min(
                                        value.stableFrames(),
                                        Math.max(0, sequence - targetAfterSequence)));
    }

    private void resetTracking() {
        synchronized (lock) {
            targetAfterSequence = sequence;
        }
        source.resetTracking();
    }

    private PhysicalMotionService.Scene physicalScene() {
        synchronized (lock) {
            VisionPort.Observation value = observation;
            return new PhysicalMotionService.Scene(
                    sequence,
                    value == null ? 0 : value.visibleCount(),
                    physicalTarget(),
                    visionHealthy(),
                    value == null ? null : value.calibrationSha256());
        }
    }

    public void physicalCommand(String action) {
        if (physical == null || !"live".equals(sourceMode))
            throw new IllegalStateException("El backend no fue iniciado en modo físico.");
        if ("arm".equals(action) && !visionHealthy())
            throw new IllegalStateException(
                    "La cámara debe estar disponible para preparar el ciclo físico.");
        physical.command(action);
    }

    public void virtualCommand(String action) {
        if (physical != null)
            throw new IllegalStateException("No se admiten comandos virtuales en modo físico.");
        synchronized (lock) {
            switch (action) {
                case "pause" -> virtual.paused(true);
                case "resume" -> virtual.paused(false);
                case "cycle" -> {
                    if (!visionHealthy())
                        throw new IllegalStateException("La cámara debe estar disponible.");
                    Detection target =
                            "live".equals(sourceMode)
                                    ? physicalTarget()
                                    : Detection.demo(summary.total());
                    if (target == null)
                        throw new IllegalStateException(
                                "Se requieren un objetivo estable con confianza mínima de 0.80.");
                    virtual.start(target);
                    autoCycle = false;
                    virtual.paused(false);
                    previousTick = System.nanoTime();
                }
                default -> throw new IllegalStateException("Comando virtual desconocido.");
            }
        }
    }

    public MonitorSnapshot snapshot() {
        PhysicalMotionService.View robot = physical == null ? null : physical.snapshot();
        synchronized (lock) {
            VisionPort.Observation view = observation;
            Detection detected = view == null ? null : view.detection();
            if ("simulation".equals(sourceMode) && virtual.detection() != null)
                detected = virtual.detection();
            Detection target = view == null ? null : view.targetDetection();
            if ("simulation".equals(sourceMode)) target = detected;
            if (virtual.active()) target = virtual.detection();
            if (robot != null && robot.target() != null) target = robot.target();
            List<MonitorSnapshot.Equipment> equipment =
                    List.of("camera", "yolo", "robot", "gripper", "planner").stream()
                            .map(
                                    id ->
                                            new MonitorSnapshot.Equipment(
                                                    id,
                                                    List.of("camera", "yolo").contains(id)
                                                            ? (visionHealthy()
                                                                    ? "online"
                                                                    : "offline")
                                                            : robot == null
                                                                    ? "unknown"
                                                                    : robot.robotState().connected()
                                                                            ? "online"
                                                                            : "offline"))
                            .toList();
            return new MonitorSnapshot(
                    "baseline",
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    sourceMode,
                    robot == null ? "simulation" : "physical",
                    "sse",
                    robot == null ? null : robot.robotState(),
                    "mjpeg",
                    new MonitorSnapshot.SimulationState(
                            virtual.paused(),
                            virtual.active(),
                            effectiveStableFrames(view),
                            profile.integer("STABLE_FRAMES"),
                            physicalTarget() != null),
                    detected,
                    target,
                    robot == null ? virtual.process() : robot.process(),
                    summary,
                    operations,
                    equipment,
                    Math.round(fps * 100) / 100.0);
        }
    }

    public Map<String, Object> status() {
        PhysicalMotionService.View robot = physical == null ? null : physical.snapshot();
        synchronized (lock) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sourceMode", sourceMode);
            data.put("motionMode", robot == null ? "simulation" : "physical");
            data.put("physicalMotionEnabled", robot != null);
            data.put("robotState", robot == null ? null : robot.robotState());
            data.put("cameraOnline", visionHealthy());
            data.put("error", error);
            data.put("paused", virtual.paused());
            data.put("autoCycle", autoCycle);
            data.put(
                    "cycleActive",
                    robot == null ? virtual.active() : robot.robotState().cycleActive());
            data.put("stableFrames", effectiveStableFrames(observation));
            data.put("requiredStableFrames", profile.integer("STABLE_FRAMES"));
            data.put("provisionalDestinations", profile.provisionalDestinations());
            data.put("destinations", profile.bins());
            data.put("frameSequence", sequence);
            return data;
        }
    }

    public Frame frame() {
        synchronized (lock) {
            return new Frame(sequence, visionHealthy() ? observation.jpeg() : null);
        }
    }

    public Frame waitFrame(long after) throws InterruptedException {
        synchronized (lock) {
            if (running && sequence <= after && observation != null) lock.wait(1000);
            return frame();
        }
    }

    public boolean running() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        ticker.shutdownNow();
        synchronized (lock) {
            lock.notifyAll();
        }
        if (physical != null) physical.close();
        source.close();
    }
}
