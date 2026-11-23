package com.back.rony.backrony.robotics.domain;

import java.util.List;

/** Two fixed operator commands. No coordinates, servo angles or shell code from the browser. */
public final class ManualControls {
    private ManualControls() {}

    public static boolean isAction(String action) {
        return "close-gripper".equals(action) || "park".equals(action);
    }

    public static RobotPose.Position storage() {
        return new RobotPose.Position(-42.6878, -27.0905, 225.0);
    }

    public static List<MotionPlan.Step> steps(String action, RobotPose pose, RobotProfile profile) {
        if ("close-gripper".equals(action)) {
            return List.of(
                    new MotionPlan.Step(0, "Cerrando pinza manualmente a 90°", null, 1.0, 90));
        }
        if ("park".equals(action)) {
            if (!pose.near(profile.home())) {
                throw new IllegalStateException(
                        "Vuelve a HOME con Resetear antes de guardar el brazo.");
            }
            return List.of(
                    new MotionPlan.Step(
                            0, "Moviendo a posición de guardado", storage(), 3.0, null));
        }
        throw new IllegalArgumentException("Control manual desconocido.");
    }
}
