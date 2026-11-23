package com.back.rony.backrony.robotics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.back.rony.backrony.robotics.domain.RobotPose;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PhysicalMotionServiceTest {
    static final class FakeHistory implements HistoryPort {
        final AtomicInteger grasps = new AtomicInteger();
        final List<Operation> operations = new CopyOnWriteArrayList<>();

        public void saveOperation(Operation o, String source) {
            operations.add(o);
        }

        public List<Operation> recentOperations() {
            return List.of();
        }

        public Summary summary(Instant since) {
            return new Summary(0, 0, 0, 0, 0);
        }

        public long countGrasps() {
            return grasps.get();
        }

        public void saveGrasp(Detection detection) {
            grasps.incrementAndGet();
        }
    }

    static final class FakeRobot implements RobotPort {
        volatile RobotPose pose = new RobotPose(0, -162.94, 212.8, 90);
        Consumer<RobotPose> observed = p -> {};
        final List<MotionPlan.Step> executed = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean block, fail;
        volatile String calibration;

        public void connect(Consumer<RobotPose> telemetry) {
            observed = telemetry;
            observed.accept(pose);
        }

        public RobotPose currentPose() {
            observed.accept(pose);
            return pose;
        }

        public void preflight(List<RobotPose.Position> points) {}

        public String calibrationSha256() {
            return calibration;
        }

        public RobotPose execute(MotionPlan.Step step, RobotPose expected) {
            if (fail) throw new IllegalStateException("Fake lost ROS");
            executed.add(step);
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS))
                        throw new IllegalStateException("Fake timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                block = false;
            }
            pose =
                    step.position() == null
                            ? new RobotPose(pose.x(), pose.y(), pose.z(), step.gripper())
                            : new RobotPose(
                                    step.position().x(),
                                    step.position().y(),
                                    step.position().z(),
                                    pose.gripperAngle());
            observed.accept(pose);
            return pose;
        }

        public void close() {}
    }

    static void await(PhysicalMotionService service, String state) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (!state.equals(service.snapshot().robotState().state()) && System.nanoTime() < end)
            Thread.sleep(20);
        assertEquals(state, service.snapshot().robotState().state());
    }

    static void awaitCondition(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void rejectsVisionFromAnotherCalibrationBeforeAnyMotion() throws Exception {
        FakeRobot robot = new FakeRobot();
        robot.calibration = "robot-calibration";
        AtomicReference<String> visionCalibration = new AtomicReference<>("old-calibration");
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () ->
                                new PhysicalMotionService.Scene(
                                        0, 1, null, true, visionCalibration.get()),
                        () -> {},
                        TestProfile.profile(),
                        new FakeHistory())) {
            service.start();
            await(service, "idle");
            assertThrows(IllegalStateException.class, () -> service.command("arm"));
            assertTrue(robot.executed.isEmpty());
            assertEquals("idle", service.snapshot().robotState().state());
            visionCalibration.set(robot.calibration);
            service.command("arm");
            await(service, "waiting");
            assertEquals(2, robot.executed.size());
        }
    }

    @Test
    void manualControlsDoNotStartClassificationAndParkingCanBeReset() throws Exception {
        FakeRobot robot = new FakeRobot();
        FakeHistory history = new FakeHistory();
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () -> new PhysicalMotionService.Scene(0, 0, null, false),
                        () -> {},
                        TestProfile.profile(),
                        history)) {
            service.start();
            await(service, "idle");
            service.command("close-gripper");
            awaitCondition(() -> !service.snapshot().robotState().cycleActive());
            assertEquals(90, robot.pose.gripperAngle());
            assertFalse(service.snapshot().robotState().autoEnabled());
            service.command("park");
            await(service, "parked");
            assertTrue(robot.pose.near(ManualControls.storage()));
            assertThrows(IllegalStateException.class, () -> service.command("arm"));
            assertTrue(history.operations.isEmpty());
            assertEquals(0, history.grasps.get());
            service.command("reset");
            await(service, "idle");
            assertTrue(robot.pose.near(TestProfile.profile().home()));
            assertEquals(0, robot.pose.gripperAngle());
        }
    }

    @Test
    void startupOnlyReadsAndResetDoesNotArm() throws Exception {
        FakeRobot robot = new FakeRobot();
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () -> new PhysicalMotionService.Scene(0, 1, null, true),
                        () -> {},
                        TestProfile.profile(),
                        new FakeHistory())) {
            service.start();
            await(service, "idle");
            assertTrue(robot.executed.isEmpty());
            assertFalse(service.snapshot().robotState().autoEnabled());
            service.command("reset");
            await(service, "idle");
            assertEquals(0, robot.pose.gripperAngle());
            assertFalse(service.snapshot().robotState().autoEnabled());
        }
    }

    @Test
    void cancelLetsCurrentSegmentFinishBlocksNextAndResetRemainsUsable() throws Exception {
        FakeRobot robot = new FakeRobot();
        robot.block = true;
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () -> new PhysicalMotionService.Scene(0, 1, null, true),
                        () -> {},
                        TestProfile.profile(),
                        new FakeHistory())) {
            service.start();
            await(service, "idle");
            service.command("arm");
            assertTrue(robot.entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.command("close-gripper"));
            assertThrows(IllegalStateException.class, () -> service.command("park"));
            service.command("cancel");
            assertThrows(IllegalStateException.class, () -> service.command("reset"));
            robot.release.countDown();
            await(service, "cancelled");
            assertEquals(1, robot.executed.size());
            service.command("reset");
            await(service, "idle");
            assertEquals(0, robot.pose.gripperAngle());
        }
    }

    @Test
    void repeatedObjectIsDeferredWithoutStoppingClassification() throws Exception {
        FakeRobot robot = new FakeRobot();
        FakeHistory history = new FakeHistory();
        AtomicReference<Detection> detection = new AtomicReference<>();
        AtomicInteger deferred = new AtomicInteger();
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () -> new PhysicalMotionService.Scene(0, 1, detection.get(), true),
                        () -> {},
                        TestProfile.profile(),
                        history,
                        target -> {
                            deferred.incrementAndGet();
                            detection.set(null);
                        })) {
            service.start();
            await(service, "idle");
            service.command("arm");
            await(service, "waiting");
            detection.set(TestProfile.target("plastic"));
            awaitCondition(() -> deferred.get() == 1);
            await(service, "waiting");
            assertEquals(0, robot.pose.gripperAngle());
            assertTrue(robot.pose.near(TestProfile.profile().home()));
            assertEquals(3, history.grasps.get());
            assertEquals(3, history.operations.size());
            assertTrue(
                    history.operations.stream()
                            .allMatch(
                                    o ->
                                            "unverified".equals(o.result())
                                                    && o.depositConfirmed() == null));
            assertNull(service.snapshot().robotState().holdingObject());
            assertFalse(service.snapshot().process().completed());
            assertNull(service.snapshot().target());
            assertTrue(service.snapshot().robotState().autoEnabled());
            service.command("cancel");
            assertFalse(service.snapshot().robotState().autoEnabled());
        }
    }

    @Test
    void automaticallyReanalyzesAfterEachHomeAndAcceptsNewObjectsLater() throws Exception {
        FakeRobot robot = new FakeRobot();
        FakeHistory history = new FakeHistory();
        AtomicReference<Detection> detection = new AtomicReference<>();
        AtomicInteger searches = new AtomicInteger();
        Runnable analyzeAgain =
                () -> {
                    int search = searches.incrementAndGet();
                    detection.set(
                            switch (search) {
                                case 1 -> TestProfile.target("plastic");
                                case 2 -> TestProfile.target("aluminum");
                                case 3 -> TestProfile.target("cardboard");
                                default -> null;
                            });
                };
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () ->
                                new PhysicalMotionService.Scene(
                                        searches.get(),
                                        detection.get() == null ? 0 : 1,
                                        detection.get(),
                                        true),
                        analyzeAgain,
                        TestProfile.profile(),
                        history)) {
            service.start();
            await(service, "idle");
            service.command("arm");
            awaitCondition(() -> searches.get() >= 4);
            assertEquals(3, history.grasps.get());
            assertEquals(
                    List.of("plastic", "aluminum", "cardboard"),
                    history.operations.stream().map(Operation::category).toList());
            assertTrue(service.snapshot().robotState().autoEnabled());
            assertTrue(robot.pose.near(TestProfile.profile().home()));
            detection.set(TestProfile.target("plastic"));
            awaitCondition(() -> history.grasps.get() >= 4);
            await(service, "waiting");
            assertEquals(4, history.grasps.get());
            service.command("cancel");
        }
    }

    @Test
    void emptySceneAndSearchTimeoutLeaveTheAutomaticModeEnabled() throws Exception {
        FakeRobot robot = new FakeRobot();
        var original = TestProfile.profile();
        var values = new HashMap<>(original.values());
        values.put("SEARCH_TIMEOUT", .05);
        values.put("EMPTY_CONFIRM_FRAMES", 2.0);
        var profile =
                new com.back.rony.backrony.robotics.domain.RobotProfile(
                        values,
                        original.bins(),
                        original.enabledCategories(),
                        original.pickupWorkspace(),
                        original.provisionalDestinations(),
                        original.sha256());
        AtomicInteger sequence = new AtomicInteger();
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () ->
                                new PhysicalMotionService.Scene(
                                        sequence.incrementAndGet(), 0, null, true),
                        () -> {},
                        profile,
                        new FakeHistory())) {
            service.start();
            await(service, "idle");
            service.command("arm");
            await(service, "waiting");
            Thread.sleep(350);
            assertEquals("waiting", service.snapshot().robotState().state());
            assertTrue(service.snapshot().robotState().autoEnabled());
            assertEquals(2, robot.executed.size());
            service.command("cancel");
        }
    }

    @Test
    void faultStopsCommandsAndResetReconnectsWithoutRestartingService() throws Exception {
        FakeRobot robot = new FakeRobot();
        robot.fail = true;
        try (var service =
                new PhysicalMotionService(
                        () -> robot,
                        () -> new PhysicalMotionService.Scene(0, 1, null, true),
                        () -> {},
                        TestProfile.profile(),
                        new FakeHistory())) {
            service.start();
            await(service, "idle");
            service.command("arm");
            await(service, "fault");
            assertFalse(service.snapshot().robotState().connected());
            robot.fail = false;
            service.command("reset");
            await(service, "idle");
            assertNull(service.snapshot().robotState().error());
        }
    }
}
