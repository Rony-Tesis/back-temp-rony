package com.back.rony.backrony.robotics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.back.rony.backrony.robotics.domain.RobotPose;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PhysicalMotionServiceTest {
    static final class FakeHistory implements HistoryPort {
        final AtomicInteger grasps = new AtomicInteger();

        public void saveOperation(Operation o, String source) {
            fail("A grasp cannot create a classification");
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

        public void connect(Consumer<RobotPose> telemetry) {
            observed = telemetry;
            observed.accept(pose);
        }

        public RobotPose currentPose() {
            observed.accept(pose);
            return pose;
        }

        public void preflight(List<RobotPose.Position> points) {}

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

    @Test
    void startupOnlyReadsAndResetDoesNotArm() throws Exception {
        FakeRobot robot = new FakeRobot();
        try (var service =
                new PhysicalMotionService(() -> robot, () -> null, () -> true, new FakeHistory())) {
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
                new PhysicalMotionService(() -> robot, () -> null, () -> true, new FakeHistory())) {
            service.start();
            await(service, "idle");
            service.command("arm");
            assertTrue(robot.entered.await(2, TimeUnit.SECONDS));
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
    void aCompletePlanReturnsHomeClosedAndRecordsOnlyOneGrasp() throws Exception {
        FakeRobot robot = new FakeRobot();
        FakeHistory history = new FakeHistory();
        AtomicReference<Detection> detection = new AtomicReference<>();
        try (var service =
                new PhysicalMotionService(() -> robot, detection::get, () -> true, history)) {
            service.start();
            await(service, "idle");
            service.command("arm");
            await(service, "waiting");
            detection.set(Detection.demo(0));
            await(service, "holding");
            assertEquals(82, robot.pose.gripperAngle());
            assertTrue(robot.pose.near(MotionPlan.HOME));
            assertEquals(1, history.grasps.get());
            assertNull(service.snapshot().robotState().holdingObject());
            assertFalse(service.snapshot().process().completed());
            assertFalse(service.snapshot().robotState().autoEnabled());
            assertThrows(IllegalStateException.class, () -> service.command("arm"));
        }
    }

    @Test
    void faultStopsCommandsAndResetReconnectsWithoutRestartingService() throws Exception {
        FakeRobot robot = new FakeRobot();
        robot.fail = true;
        try (var service =
                new PhysicalMotionService(() -> robot, () -> null, () -> true, new FakeHistory())) {
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
