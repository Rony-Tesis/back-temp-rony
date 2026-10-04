package com.back.rony.backrony.robotics.application.port;

import com.back.rony.backrony.robotics.domain.MotionPlan.Step;
import com.back.rony.backrony.robotics.domain.RobotPose;
import java.util.List;
import java.util.function.Consumer;

/** Typed adapter boundary. No arbitrary coordinates or shell commands from HTTP. */
public interface RobotPort extends AutoCloseable {
    void connect(Consumer<RobotPose> telemetry);

    RobotPose currentPose();

    void preflight(List<RobotPose.Position> positions);

    RobotPose execute(Step step, RobotPose expected);

    @Override
    void close();
}
