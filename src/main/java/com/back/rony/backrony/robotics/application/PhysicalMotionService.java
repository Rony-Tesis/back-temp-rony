package com.back.rony.backrony.robotics.application;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.ProcessState;
import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.back.rony.backrony.robotics.domain.RobotPose;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Perception coordinator; trajectories and motion timers execute on the JetMax agent. */
public final class PhysicalMotionService implements AutoCloseable {
    public record Scene(
            long sequence,
            int visibleCount,
            Detection target,
            boolean healthy,
            String calibrationSha256) {
        public Scene(long sequence, int visibleCount, Detection target, boolean healthy) {
            this(sequence, visibleCount, target, healthy, null);
        }
    }

    private final Supplier<RobotPort> factory;
    private final Supplier<Scene> scene;
    private final Runnable resetVision;
    private final RobotProfile profile;
    private final HistoryPort history;
    private final Consumer<Detection> deferTarget;
    private final Consumer<Boolean> motionActive;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("rony-physical").factory());
    private volatile boolean cancelled, stopped;
    private volatile RobotPort robot;
    private volatile Telemetry telemetry;
    private String state = "connecting", label = "Conectando con el agente JetMax", error;
    private boolean enabled, active, holding, completed, cycleRecorded;
    private int stage, emptyFrames, repeats;
    private long started,
            stageStarted,
            elapsedFrozen,
            stageFrozen,
            grasps,
            cycles,
            searchStarted,
            lastSequence;
    private final long[] durations = new long[6];
    private Detection target, previousTarget;
    private String cycleId, lastResult;
    private String manualAction;

    private record Telemetry(RobotPose pose, long at, Instant timestamp) {}

    public PhysicalMotionService(
            Supplier<RobotPort> factory,
            Supplier<Scene> scene,
            Runnable resetVision,
            RobotProfile profile,
            HistoryPort history) {
        this(factory, scene, resetVision, profile, history, target -> {});
    }

    public PhysicalMotionService(
            Supplier<RobotPort> factory,
            Supplier<Scene> scene,
            Runnable resetVision,
            RobotProfile profile,
            HistoryPort history,
            Consumer<Detection> deferTarget) {
        this(factory, scene, resetVision, profile, history, deferTarget, active -> {});
    }

    public PhysicalMotionService(
            Supplier<RobotPort> factory,
            Supplier<Scene> scene,
            Runnable resetVision,
            RobotProfile profile,
            HistoryPort history,
            Consumer<Detection> deferTarget,
            Consumer<Boolean> motionActive) {
        this.factory = factory;
        this.scene = scene;
        this.resetVision = resetVision;
        this.profile = profile;
        this.history = history;
        this.deferTarget = deferTarget;
        this.motionActive = motionActive;
        this.grasps = history.countGrasps();
        this.cycles = history.countPhysicalCycles();
    }

    public void start() {
        worker.execute(
                () -> {
                    try {
                        connect();
                        set("idle", "Listo para activar; destinos provisionales", 0);
                    } catch (RuntimeException e) {
                        failure(e);
                    }
                });
        worker.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void connect() {
        robot = factory.get();
        robot.connect(
                p ->
                        telemetry =
                                new Telemetry(
                                        p,
                                        System.nanoTime(),
                                        Instant.now()
                                                .truncatedTo(
                                                        java.time.temporal.ChronoUnit.MILLIS)));
        robot.currentPose();
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
            else if ("manual".equals(current) && busy) manual();
            else if (robot != null) {
                robot.currentPose();
                if (auto && "preparing".equals(current)) prepare();
                else if (auto && "waiting".equals(current)) search();
            }
        } catch (CancellationException e) {
            recordInterrupted("failure");
            freeze("cancelled", "Cancelado; revisa el recorrido antes de resetear");
            disconnect();
        } catch (RuntimeException e) {
            failure(e);
        }
    }

    private void failure(RuntimeException e) {
        recordInterrupted("failure");
        synchronized (this) {
            String detail =
                    e.getMessage() == null ? "Revisa agente, ROS y calibración" : e.getMessage();
            error = detail.substring(0, Math.min(200, detail.length()));
        }
        freeze("fault", "Movimiento bloqueado; revisión y reset requeridos");
        disconnect();
    }

    private void guard(boolean requireVision) {
        if (cancelled || stopped) throw new CancellationException();
        if (requireVision) {
            Scene view = scene.get();
            if (!view.healthy()) {
                throw new IllegalStateException(
                        "Cámara no disponible; siguientes tramos bloqueados.");
            }
            String calibration = robot.calibrationSha256();
            if (calibration != null && !calibration.equals(view.calibrationSha256())) {
                throw new IllegalStateException(
                        "La visión aún no usa la calibración vigente del brazo; espera nuevos frames.");
            }
        }
    }

    private RobotPose run(List<MotionPlan.Step> steps, boolean requireVision, String runningState) {
        RobotPose expected = robot.currentPose();
        motionActive.accept(true);
        try {
            return robot.executePlan(
                    steps,
                    expected,
                    () -> guard(requireVision),
                    step -> set(runningState, step.label(), step.stage()),
                    step -> {
                        synchronized (this) {
                            if (step.gripper() != null) holding = step.gripper() != 0;
                        }
                    });
        } finally {
            motionActive.accept(false);
        }
    }

    private void prepare() {
        guard(true);
        run(MotionPlan.prepare(profile, robot.currentPose()), true, "preparing");
        beginSearch();
    }

    private void beginSearch() {
        resetVision.run();
        Scene view = scene.get();
        synchronized (this) {
            active = false;
            target = null;
            completed = false;
            cycleId = null;
            started = stageStarted = elapsedFrozen = stageFrozen = 0;
            java.util.Arrays.fill(durations, 0);
            searchStarted = System.nanoTime();
            lastSequence = view.sequence();
            emptyFrames = 0;
            set(
                    "waiting",
                    "Analizando mesa · " + profile.integer("STABLE_FRAMES") + " frames estables",
                    1);
        }
    }

    private void search() {
        guard(true);
        Scene view = scene.get();
        if (view.sequence() != lastSequence) {
            lastSequence = view.sequence();
            emptyFrames = view.visibleCount() == 0 ? emptyFrames + 1 : 0;
            if (emptyFrames >= profile.integer("EMPTY_CONFIRM_FRAMES")) {
                previousTarget = null;
                repeats = 0;
                set("waiting", "Mesa vacía; automático activo, esperando nuevos residuos", 1);
            }
        }
        Detection detected = view.target();
        if (detected != null) {
            try {
                MotionPlan.sort(detected, profile);
            } catch (IllegalArgumentException invalid) {
                detected = null;
            }
            if (detected != null) {
                sort(detected);
                return;
            }
        }
        if (System.nanoTime() - searchStarted >= (long) (profile.value("SEARCH_TIMEOUT") * 1e9)) {
            set("waiting", "Automático activo; esperando un residuo estable y apto", 1);
        }
    }

    private void sort(Detection detected) {
        List<MotionPlan.Step> steps = MotionPlan.sort(detected, profile);
        if (!robot.currentPose().near(profile.home()) || robot.currentPose().gripperAngle() > 3) {
            throw new IllegalStateException(
                    "HOME y pinza abierta son obligatorios antes de recoger.");
        }
        repeats =
                previousTarget != null
                                && previousTarget.category().equals(detected.category())
                                && Math.hypot(
                                                previousTarget.targetMm().x()
                                                        - detected.targetMm().x(),
                                                previousTarget.targetMm().y()
                                                        - detected.targetMm().y())
                                        < profile.value("REPEAT_RADIUS_MM")
                        ? repeats + 1
                        : 1;
        if (repeats > profile.integer("MAX_REPEATS_SAME_OBJECT")) {
            deferTarget.accept(detected);
            previousTarget = null;
            repeats = 0;
            beginSearch();
            set("waiting", "Objeto persistente omitido; automático activo para otros residuos", 1);
            return;
        }
        previousTarget = detected;
        synchronized (this) {
            target =
                    detected.withDestination(
                            profile.destination(detected.category()).label()
                                    + " · destino provisional");
            cycleId = UUID.randomUUID().toString();
            cycleRecorded = completed = false;
            lastResult = null;
            active = true;
            started = stageStarted = System.nanoTime();
            elapsedFrozen = stageFrozen = 0;
            java.util.Arrays.fill(durations, 0);
        }
        robot.preflight(
                steps.stream()
                        .map(MotionPlan.Step::position)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        Detection current = scene.get().target();
        if (current == null
                || !current.category().equals(detected.category())
                || Math.hypot(
                                current.targetMm().x() - detected.targetMm().x(),
                                current.targetMm().y() - detected.targetMm().y())
                        > 5) {
            cycleId = null;
            beginSearch();
            return;
        }
        // No countdown: preserve the current notebook's immediate, fresh-target sequence.
        run(steps, true, "moving");
        history.saveGrasp(detected);
        record("unverified");
        synchronized (this) {
            grasps++;
            cycles++;
            holding = false;
            completed = true;
            lastResult = "unverified";
        }
        freeze("finished", "Trayectoria completada; depósito pendiente de comprobación visual");
        synchronized (this) {
            enabled = true;
        }
        beginSearch();
    }

    private void record(String result) {
        if (cycleId == null || cycleRecorded || target == null) return;
        history.saveOperation(
                new Operation(
                        cycleId,
                        Instant.now(),
                        target.residue(),
                        target.category(),
                        target.confidence(),
                        result,
                        Math.min(86400000, millis(System.nanoTime() - started)),
                        target.destination(),
                        null),
                "live");
        cycleRecorded = true;
    }

    private void recordInterrupted(String result) {
        try {
            record(result);
        } catch (RuntimeException ignored) {
            /* Preserve the movement fault even if persistence is unavailable. */
        }
    }

    private void reset() {
        guard(false);
        if (robot == null) connect();
        run(MotionPlan.reset(robot.currentPose(), profile), false, "resetting");
        resetVision.run();
        synchronized (this) {
            target = previousTarget = null;
            cycleId = lastResult = error = null;
            active = enabled = holding = completed = false;
            repeats = 0;
            started = stageStarted = elapsedFrozen = stageFrozen = 0;
            java.util.Arrays.fill(durations, 0);
            cancelled = false;
            set("idle", "Reset completo · HOME y pinza abierta", 0);
        }
    }

    private void manual() {
        guard(false);
        String action;
        synchronized (this) {
            action = manualAction;
        }
        robot.executeManual(
                action,
                robot.currentPose(),
                profile,
                () -> guard(false),
                step -> set("manual", step.label(), 0),
                step -> {
                    if (step.gripper() != null)
                        synchronized (this) {
                            holding = step.gripper() != 0;
                        }
                });
        synchronized (this) {
            manualAction = null;
            target = null;
            completed = false;
            error = null;
        }
        boolean parked = robot.currentPose().near(ManualControls.storage());
        freeze(
                parked ? "parked" : "manual",
                parked
                        ? "Brazo en posición de guardado; Resetear para volver a HOME"
                        : "Pinza cerrada manualmente a 90°; Resetear antes de clasificar");
    }

    public synchronized void command(String action) {
        if (ManualControls.isAction(action)) {
            if (active
                    || enabled
                    || robot == null
                    || List.of("connecting", "resetting", "fault").contains(state)) {
                throw new IllegalStateException(
                        "Detén el automático y espera antes de usar controles manuales.");
            }
            ManualControls.steps(action, robot.currentPose(), profile);
            manualAction = action;
            cancelled = completed = enabled = false;
            active = true;
            cycleId = lastResult = error = null;
            started = stageStarted = System.nanoTime();
            java.util.Arrays.fill(durations, 0);
            set("manual", "Control manual solicitado", 0);
            return;
        }
        if ("reset".equals(action)) {
            if (active || List.of("connecting", "resetting").contains(state)) {
                throw new IllegalStateException(
                        "Cancela y espera que termine el tramo antes de resetear.");
            }
            enabled = cancelled = false;
            active = true;
            completed = false;
            cycleId = null;
            error = null;
            started = stageStarted = System.nanoTime();
            java.util.Arrays.fill(durations, 0);
            set("resetting", "Reset solicitado · HOME y apertura de pinza", 0);
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
        if (!"arm".equals(action)
                || !List.of("idle", "finished").contains(state)
                || active
                || holding
                || robot == null) {
            throw new IllegalStateException(
                    "Activa con HOME, pinza vacía y sin otra trayectoria activa.");
        }
        robot.currentPose();
        guard(true);
        enabled = active = true;
        cancelled = completed = false;
        cycleId = lastResult = null;
        previousTarget = null;
        repeats = 0;
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
        elapsedFrozen = started == 0 ? 0 : millis(now - started);
        stageFrozen = stageStarted == 0 ? 0 : millis(now - stageStarted);
        durations[stage] += stageFrozen;
        state = value;
        label = text;
        active = enabled = false;
        stageStarted = 0;
    }

    public synchronized View snapshot() {
        long now = System.nanoTime();
        Telemetry t = telemetry;
        boolean connected =
                robot != null
                        && t != null
                        && millis(now - t.at()) <= 2000
                        && !"fault".equals(state);
        long elapsed = active && started != 0 ? millis(now - started) : elapsedFrozen;
        long stageElapsed = active && stageStarted != 0 ? millis(now - stageStarted) : stageFrozen;
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
                        pose == null ? null : pose.joints(),
                        t == null ? null : t.timestamp(),
                        error,
                        cycles,
                        profile.provisionalDestinations());
        boolean done = completed && !active && pose != null && pose.gripperAngle() <= 3;
        ProcessState process =
                new ProcessState(
                        stage,
                        Math.min(86400000, elapsed),
                        Math.min(86400000, stageElapsed),
                        stageTimes,
                        done ? 100 : stage / 6.0 * 100,
                        done,
                        done ? lastResult : null,
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
            RobotPose.JointAngles reportedJoints,
            Instant telemetryAt,
            String error,
            long completedCycles,
            boolean provisionalDestinations) {}

    public record ReportedPosition(double x, double y, double z) {}

    public record View(RobotState robotState, Detection target, ProcessState process) {}

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }

    @Override
    public void close() {
        stopped = cancelled = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disconnect();
    }
}
