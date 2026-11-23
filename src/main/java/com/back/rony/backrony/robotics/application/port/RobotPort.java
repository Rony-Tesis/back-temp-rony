package com.back.rony.backrony.robotics.application.port;

import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.MotionPlan.Step;
import com.back.rony.backrony.robotics.domain.RobotPose;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.util.List;
import java.util.function.Consumer;

/** Typed adapter boundary. No arbitrary coordinates or shell commands from HTTP. */
public interface RobotPort extends AutoCloseable {
    void connect(Consumer<RobotPose> telemetry);

    RobotPose currentPose();

    default String calibrationSha256() {
        return null;
    }

    void preflight(List<RobotPose.Position> positions);

    RobotPose execute(Step step, RobotPose expected);

    default RobotPose executeManual(
            String action,
            RobotPose expected,
            RobotProfile profile,
            Runnable guard,
            Consumer<Step> started,
            Consumer<Step> completed) {
        return executePlan(
                ManualControls.steps(action, expected, profile),
                expected,
                guard,
                started,
                completed);
    }

    /** A remote executor overrides this to receive the complete trajectory locally. */
    default RobotPose executePlan(
            List<Step> steps,
            RobotPose expected,
            Runnable guard,
            Consumer<Step> started,
            Consumer<Step> completed) {
        for (Step step : steps) {
            guard.run();
            started.accept(step);
            expected = execute(step, expected);
            completed.accept(step);
            guard.run();
        }
        return expected;
    }

    @Override
    void close();
}
