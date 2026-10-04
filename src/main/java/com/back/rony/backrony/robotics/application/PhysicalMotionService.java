package com.back.rony.backrony.robotics.application;

import static com.back.rony.backrony.robotics.domain.MotionPlan.HOME;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.ProcessState;
import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.back.rony.backrony.robotics.domain.RobotPose;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One actuator worker, independent of camera and HTTP streaming. Startup never moves. */
public final class PhysicalMotionService implements AutoCloseable {
    private final Supplier<RobotPort> factory;
    private final Supplier<Detection> targetProvider;
    private final BooleanSupplier visionHealthy;
    private final HistoryPort history;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("rony-physical").factory());
    private volatile boolean cancelled;
    private volatile boolean stopped;
    private volatile RobotPort robot;
    private volatile Telemetry telemetry;
    private String state = "connecting", label = "Conectando con ROS", error;
    private boolean enabled, active, holding;
    private int stage;
    private long started, stageStarted, frozenElapsed, frozenStage, grasps;
    private final long[] durations = new long[6];
    private Detection target;

    private record Telemetry(RobotPose pose, long at, Instant timestamp) {}

    public PhysicalMotionService(
            Supplier<RobotPort> factory,
            Supplier<Detection> targetProvider,
            BooleanSupplier visionHealthy,
            HistoryPort history) {
        this.factory = factory;
        this.targetProvider = targetProvider;
        this.visionHealthy = visionHealthy;
        this.history = history;
        this.grasps = history.countGrasps();
    }

    public void start() {
        worker.execute(
                () -> {
                    try {
                        connect();
                        set("idle", "Listo para activar el funcionamiento", 0);
                    } catch (RuntimeException e) {
                        failure();
                    }
                });
        worker.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void connect() {
        RobotPort next = factory.get();
        robot = next;
        next.connect(
                p ->
                        telemetry =
                                new Telemetry(
                                        p,
                                        System.nanoTime(),
                                        Instant.now()
                                                .truncatedTo(
                                                        java.time.temporal.ChronoUnit.MILLIS)));
        next.currentPose();
    }

    private void disconnect() {
        RobotPort current = robot;
        robot = null;
        telemetry = null;
        if (current != null) current.close();
    }

    private void tick() {
        if (stopped) return;
        try {
            String current;
            boolean auto, busy;
            synchronized (this) {
                current = state;
                auto = enabled;
                busy = active;
            }
            if (cancelled && busy) throw new CancellationException();
            if ("resetting".equals(current)) reset();
            else if (robot != null) {
                robot.currentPose();
                if (auto && "preparing".equals(current)) prepare();
                else if (auto && "waiting".equals(current)) {
                    Detection detected = targetProvider.get();
                    if (detected != null) {
                        try {
                            MotionPlan.grasp(detected);
                        } catch (IllegalArgumentException invalid) {
                            synchronized (this) {
                                if ("waiting".equals(state)) label = invalid.getMessage();
                            }
                            return;
                        }
                        grasp(detected);
                    }
                }
            }
        } catch (CancellationException e) {
            freeze("cancelled", "Cancelado; revisa el recorrido y pulsa Resetear");
            disconnect();
        } catch (RuntimeException e) {
            failure();
        }
    }

    private void failure() {
        synchronized (this) {
            error = "Movimiento bloqueado: revisa ROS, HOME, calibración y zona de trabajo.";
        }
        freeze("fault", "Revisión requerida antes de resetear");
        disconnect();
    }

    private void checkCancellation() {
        if (cancelled || stopped) throw new CancellationException();
    }

    private RobotPose execute(MotionPlan.Step step, RobotPose expected) {
        checkCancellation();
        RobotPose pose = robot.execute(step, expected);
        checkCancellation();
        return pose;
    }

    private void prepare() {
        checkCancellation();
        RobotPose expected = robot.currentPose();
        if (!expected.near(HOME))
            throw new IllegalStateException("Para preparar el ciclo el brazo debe estar en HOME.");
        robot.preflight(List.of(HOME));
        set("preparing", "Posicionando en HOME antes de detectar", 0);
        expected = execute(new MotionPlan.Step(0, "HOME de preparación", HOME, 2, null), expected);
        set("preparing", "Abriendo pinza vacía en HOME", 0);
        execute(new MotionPlan.Step(0, "Abriendo pinza vacía", null, 1, 0), expected);
        synchronized (this) {
            set("waiting", "Esperando plástico estable (≥0.80 · 50 frames)", 1);
            active = false;
            started = stageStarted = 0;
        }
    }

    private void reset() {
        checkCancellation();
        if (robot == null) connect();
        RobotPose expected = robot.currentPose();
        List<MotionPlan.Step> steps = MotionPlan.reset(expected);
        robot.preflight(
                steps.stream()
                        .map(MotionPlan.Step::position)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        for (MotionPlan.Step step : steps) {
            set("resetting", step.label(), step.stage());
            expected = execute(step, expected);
        }
        synchronized (this) {
            target = null;
            holding = false;
            error = null;
            active = enabled = false;
            started = stageStarted = frozenElapsed = frozenStage = 0;
            java.util.Arrays.fill(durations, 0);
            cancelled = false;
            set("idle", "Reset completo · HOME y pinza abierta. Pulsa Activar funcionamiento.", 0);
        }
    }

    private void grasp(Detection detected) {
        synchronized (this) {
            if (!enabled || cancelled || !"waiting".equals(state)) return;
            active = true;
            started = stageStarted = System.nanoTime();
            set("countdown", "Validando el objetivo antes del movimiento", 1);
        }
        List<MotionPlan.Step> steps = MotionPlan.grasp(detected);
        RobotPose expected = robot.currentPose();
        if (!expected.near(HOME) || expected.gripperAngle() > 2)
            throw new IllegalStateException("HOME y pinza abierta son obligatorios.");
        robot.preflight(
                steps.stream()
                        .map(MotionPlan.Step::position)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        synchronized (this) {
            target = detected.withDestination("HOME · mantener pinza cerrada");
        }
        set("countdown", "Movimiento automático en 3 segundos", 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            checkCancellation();
            sleep(50);
        }
        Detection current = targetProvider.get();
        if (current == null
                || current.targetMm() == null
                || Math.abs(current.targetMm().x() - detected.targetMm().x()) > 5
                || Math.abs(current.targetMm().y() - detected.targetMm().y()) > 5) {
            freeze("idle", "Objetivo cambió; revisa la escena antes de activar");
            return;
        }
        for (MotionPlan.Step step : steps) {
            if (!visionHealthy.getAsBoolean())
                throw new IllegalStateException("Cámara no disponible; siguiente tramo bloqueado.");
            checkCancellation();
            set("moving", step.label(), step.stage());
            expected = execute(step, expected);
            if (Integer.valueOf(82).equals(step.gripper()))
                synchronized (this) {
                    holding = true;
                }
        }
        history.saveGrasp(detected);
        synchronized (this) {
            grasps++;
        }
        freeze("holding", "Pinza cerrada en HOME · ciclo de agarre finalizado");
    }

    public synchronized void command(String action) {
        if ("reset".equals(action)) {
            if (active || List.of("connecting", "resetting").contains(state)) {
                throw new IllegalStateException(
                        "Espera que termine el tramo o cancela antes de resetear.");
            }
            enabled = false;
            cancelled = false;
            error = null;
            active = true;
            stage = 0;
            java.util.Arrays.fill(durations, 0);
            frozenElapsed = frozenStage = 0;
            started = stageStarted = System.nanoTime();
            set("resetting", "Reset solicitado · volver a HOME y abrir la pinza", 0);
            return;
        }
        if ("cancel".equals(action)) {
            enabled = false;
            cancelled = true;
            if (!active) {
                state = "cancelled";
                label = "Automático desactivado";
            } else label = "Cancelando al terminar el tramo actual";
            return;
        }
        if (!"arm".equals(action) || !"idle".equals(state) || active || holding || robot == null) {
            throw new IllegalStateException(
                    "Activa desde HOME con pinza vacía y sin otro ciclo activo.");
        }
        robot.currentPose();
        enabled = active = true;
        cancelled = false;
        started = stageStarted = System.nanoTime();
        set("preparing", "Preparando pinza vacía", 0);
    }

    private synchronized void set(String value, String text, int nextStage) {
        long now = System.nanoTime();
        if (active && stageStarted != 0) durations[stage] += millis(now - stageStarted);
        state = value;
        label = text;
        stage = nextStage;
        stageStarted = active ? now : 0;
    }

    private synchronized void freeze(String value, String text) {
        long now = System.nanoTime();
        frozenElapsed = started == 0 ? 0 : millis(now - started);
        frozenStage = stageStarted == 0 ? 0 : millis(now - stageStarted);
        durations[stage] += frozenStage;
        state = value;
        label = text;
        active = enabled = false;
    }

    public synchronized View snapshot() {
        long now = System.nanoTime();
        Telemetry t = telemetry;
        boolean connected =
                robot != null
                        && t != null
                        && millis(now - t.at()) <= 2000
                        && !"fault".equals(state);
        long elapsed = active && started != 0 ? millis(now - started) : frozenElapsed;
        long stageElapsed = active && stageStarted != 0 ? millis(now - stageStarted) : frozenStage;
        long[] times = durations.clone();
        times[stage] = Math.max(times[stage], stageElapsed);
        List<Long> stageTimes =
                java.util.Arrays.stream(times).map(v -> Math.min(86400000, v)).boxed().toList();
        RobotPose pose = connected ? t.pose() : null;
        RobotState robotState =
                new RobotState(
                        connected,
                        state,
                        label,
                        enabled,
                        active,
                        null,
                        grasps,
                        pose == null ? null : new ReportedPosition(pose.x(), pose.y(), pose.z()),
                        pose == null ? null : pose.gripperAngle(),
                        t == null ? null : t.timestamp(),
                        error);
        ProcessState process =
                new ProcessState(
                        stage,
                        Math.min(86400000, elapsed),
                        Math.min(86400000, stageElapsed),
                        stageTimes,
                        "holding".equals(state) ? 100 : stage / 6.0 * 100,
                        false,
                        null,
                        pose != null && pose.gripperAngle() >= 75);
        return new View(robotState, target, process);
    }

    public record RobotState(
            boolean connected,
            String state,
            String stepLabel,
            boolean autoEnabled,
            boolean cycleActive,
            Boolean holdingObject,
            long completedGrasps,
            ReportedPosition reportedPosition,
            Double gripperAngle,
            Instant telemetryAt,
            String error) {}

    public record ReportedPosition(double x, double y, double z) {}

    public record View(RobotState robotState, Detection target, ProcessState process) {}

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    @Override
    public void close() {
        stopped = cancelled = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disconnect();
    }
}
