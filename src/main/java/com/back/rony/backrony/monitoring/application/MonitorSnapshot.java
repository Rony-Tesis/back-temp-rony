package com.back.rony.backrony.monitoring.application;

import com.back.rony.backrony.history.application.port.HistoryPort.Summary;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.ProcessState;
import com.back.rony.backrony.robotics.application.PhysicalMotionService.RobotState;
import java.time.Instant;
import java.util.List;

/** Compatible snapshot schema; typed fields avoid persistence entity leakage. */
public record MonitorSnapshot(
        String experimentalMode,
        Instant updatedAt,
        String sourceMode,
        String motionMode,
        String telemetryTransport,
        RobotState robotState,
        String cameraTransport,
        SimulationState simulationState,
        Detection detection,
        Detection targetDetection,
        ProcessState process,
        Summary summary,
        List<Operation> operations,
        List<Equipment> equipment,
        double framesPerSecond) {
    public record SimulationState(
            boolean paused,
            boolean cycleActive,
            int stableFrames,
            int requiredStableFrames,
            boolean targetReady) {}

    public record Equipment(String id, String status) {}
}
