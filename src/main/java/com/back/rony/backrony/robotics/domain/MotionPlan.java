package com.back.rony.backrony.robotics.domain;

import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.robotics.domain.RobotPose.Position;
import java.util.ArrayList;
import java.util.List;

/** Notebook geometry and durations. HOME with a closed gripper is not a deposit. */
public final class MotionPlan {
    public static final Position HOME = new Position(0, -162.94, 212.8);

    private MotionPlan() {}

    public record Step(
            int stage, String label, Position position, double duration, Integer gripper) {
        public Step {
            if (stage < 0
                    || stage > 5
                    || !Double.isFinite(duration)
                    || duration < 1
                    || duration > 3
                    || (position == null) == (gripper == null)
                    || (gripper != null && gripper != 0 && gripper != 82)) {
                throw new IllegalArgumentException("Tramo inválido.");
            }
        }
    }

    public static List<Step> grasp(Detection target) {
        if (target == null
                || !"plastic".equals(target.category())
                || target.confidence() < .8
                || target.targetMm() == null) {
            throw new IllegalArgumentException(
                    "Se requiere plástico estable con proyección calibrada y confianza mínima de 0.80.");
        }
        double x = target.targetMm().x(), y = target.targetMm().y();
        return List.of(
                new Step(2, "Aproximando a Z=120 mm", new Position(x, y, 120), 2, null),
                new Step(3, "Descendiendo a Z=90 mm", new Position(x, y, 90), 1, null),
                new Step(3, "Cerrando pinza a 82°", null, 1, 82),
                new Step(4, "Levantando a Z=120 mm", new Position(x, y, 120), 1.5, null),
                new Step(5, "Regresando a HOME con la pinza cerrada", HOME, 2.5, null));
    }

    public static List<Step> reset(RobotPose pose) {
        new Position(pose.x(), pose.y(), pose.z());
        List<Step> steps = new ArrayList<>();
        if (pose.z() < 120)
            steps.add(
                    new Step(
                            0,
                            "Elevando antes de volver a HOME",
                            new Position(pose.x(), pose.y(), 120),
                            1.5,
                            null));
        steps.add(new Step(0, "Reseteando: regreso a HOME", HOME, 2, null));
        steps.add(new Step(0, "Reseteando: abriendo pinza en HOME", null, 1, 0));
        return List.copyOf(steps);
    }
}
