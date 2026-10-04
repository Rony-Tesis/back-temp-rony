package com.back.rony.backrony.robotics.infrastructure;

import com.back.rony.backrony.robotics.application.port.RobotPort;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.back.rony.backrony.robotics.domain.RobotPose;
import com.back.rony.backrony.shared.infrastructure.PythonWorkerProcess;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** Native SDK transport only; cycle decisions and sequencing remain in Java. */
public final class JetMaxRobotAdapter implements RobotPort {
    private final PythonWorkerProcess worker;
    private final ObjectMapper mapper;
    private volatile RobotPose pose;
    private volatile long poseAt;
    private volatile boolean disconnected;

    public JetMaxRobotAdapter(RonyProperties properties, ObjectMapper mapper) {
        this.worker = new PythonWorkerProcess(properties, mapper);
        this.mapper = mapper;
    }

    @Override
    public void connect(Consumer<RobotPose> telemetry) {
        worker.start(
                "robot",
                value -> {
                    if ("telemetry".equals(value.path("kind").asString())) {
                        pose = mapper.treeToValue(value.path("pose"), RobotPose.class);
                        poseAt = System.nanoTime();
                        telemetry.accept(pose);
                    }
                },
                () -> disconnected = true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pose == null && !disconnected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
        }
        currentPose();
    }

    @Override
    public RobotPose currentPose() {
        RobotPose current = pose;
        if (disconnected
                || current == null
                || System.nanoTime() - poseAt > TimeUnit.SECONDS.toNanos(2)) {
            throw new IllegalStateException("Se perdió la telemetría ROS.");
        }
        return current;
    }

    @Override
    public void preflight(List<RobotPose.Position> positions) {
        List<List<Double>> points =
                positions.stream().map(p -> List.of(p.x(), p.y(), p.z())).toList();
        worker.request(Map.of("action", "preflight", "positions", points));
    }

    @Override
    public RobotPose execute(MotionPlan.Step step, RobotPose expected) {
        currentPose();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "execute");
        request.put("stage", step.stage());
        request.put("duration", step.duration());
        request.put("expected", expected);
        if (step.position() != null)
            request.put(
                    "position",
                    List.of(step.position().x(), step.position().y(), step.position().z()));
        else request.put("gripper", step.gripper());
        return mapper.treeToValue(worker.request(request).path("pose"), RobotPose.class);
    }

    @Override
    public void close() {
        disconnected = true;
        worker.close();
    }
}
