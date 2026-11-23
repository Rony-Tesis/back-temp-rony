package com.back.rony.backrony.robotics.domain;

import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.robotics.domain.RobotPose.Position;
import java.util.ArrayList;
import java.util.List;

/** Current notebook sequence, extended with three provisional static destinations. */
public final class MotionPlan {
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
                    || (gripper != null
                            && gripper != 0
                            && gripper != 80
                            && !(gripper == 90 && stage == 0 && duration == 1.0))) {
                throw new IllegalArgumentException("Tramo inválido.");
            }
        }
    }

    public static List<Step> prepare(RobotProfile p, RobotPose pose) {
        if (!pose.near(p.home())) {
            throw new IllegalStateException("Prepara el brazo en HOME antes de activar.");
        }
        return List.of(
                new Step(0, "Preparando HOME", p.home(), p.value("HOME_TIME"), null),
                new Step(0, "Abriendo pinza vacía", null, p.value("GRIPPER_OPEN_TIME"), 0));
    }

    public static List<Step> sort(Detection target, RobotProfile p) {
        if (target == null
                || target.confidence() < p.value("AUTO_MIN_CONF")
                || target.targetMm() == null) {
            throw new IllegalArgumentException(
                    "Se requiere un objetivo estable con proyección y confianza ≥0.80.");
        }
        RobotProfile.Destination bin = p.destination(target.category());
        double x = target.targetMm().x(), y = target.targetMm().y();
        if (!p.pickupWorkspace().contains(x, y, p.value("Z_PICK"))) {
            throw new IllegalArgumentException("Objetivo fuera de la zona de recogida.");
        }
        if (p.bins().values().stream()
                .anyMatch(
                        b ->
                                Math.hypot(x - b.position().x(), y - b.position().y())
                                        < p.value("DROP_EXCLUSION_MM"))) {
            throw new IllegalArgumentException("El objetivo está dentro de una zona de descarga.");
        }
        List<Step> steps =
                new ArrayList<>(
                        List.of(
                                new Step(
                                        2,
                                        "Posicionando sobre el objeto a altura HOME",
                                        new Position(x, y, p.home().z()),
                                        p.value("APPROACH_TIME"),
                                        null),
                                new Step(
                                        2,
                                        "Descendiendo verticalmente a aproximación",
                                        new Position(x, y, p.value("Z_APPROACH")),
                                        p.value("VERTICAL_TIME"),
                                        null),
                                new Step(
                                        3,
                                        "Bajando para recoger",
                                        new Position(x, y, p.value("Z_PICK")),
                                        p.value("VERTICAL_TIME"),
                                        null),
                                new Step(
                                        3,
                                        "Cerrando pinza a 80° y estabilizando",
                                        null,
                                        p.value("GRIPPER_CLOSE_TIME"),
                                        p.integer("GRIPPER_PICK")),
                                new Step(
                                        4,
                                        "Levantando verticalmente el objeto",
                                        new Position(x, y, p.value("Z_APPROACH")),
                                        p.value("VERTICAL_TIME"),
                                        null),
                                new Step(
                                        4,
                                        "Elevando verticalmente hasta altura HOME",
                                        new Position(x, y, p.home().z()),
                                        p.value("HOME_TIME"),
                                        null),
                                new Step(
                                        4,
                                        "HOME con el objeto",
                                        p.home(),
                                        p.value("HOME_TIME"),
                                        null)));
        for (Position point : bin.transitMm()) {
            steps.add(
                    new Step(
                            4,
                            "Girando hacia " + bin.label() + " · " + bin.baseAngleDeg() + "°",
                            point,
                            p.value("SIDE_TIME"),
                            null));
        }
        steps.add(
                new Step(
                        5,
                        "Abriendo pinza en destino provisional · " + bin.label(),
                        null,
                        p.value("GRIPPER_OPEN_TIME"),
                        0));
        for (Position point : returnPath(bin.transitMm(), p)) {
            steps.add(
                    new Step(
                            5,
                            "Regresando a HOME para analizar de nuevo",
                            point,
                            p.value("SIDE_TIME"),
                            null));
        }
        return List.copyOf(steps);
    }

    private static List<Position> returnPath(List<Position> outbound, RobotProfile p) {
        List<Position> result = new ArrayList<>();
        for (int i = outbound.size() - 2; i >= 0; i--) result.add(outbound.get(i));
        result.add(p.home());
        return result;
    }

    public static List<Step> reset(RobotPose pose, RobotProfile p) {
        new Position(pose.x(), pose.y(), pose.z());
        List<Step> steps = new ArrayList<>();
        if (pose.z() < p.home().z() - 2) {
            steps.add(
                    new Step(
                            0,
                            "Elevando verticalmente hasta altura HOME",
                            new Position(pose.x(), pose.y(), p.home().z()),
                            p.value("HOME_TIME"),
                            null));
        }
        List<Position> back = null;
        for (var destination : p.bins().values()) {
            for (int i = 0; i < destination.transitMm().size(); i++) {
                if (pose.near(destination.transitMm().get(i))) {
                    back = returnPath(destination.transitMm().subList(0, i + 1), p);
                    break;
                }
            }
            if (back != null) break;
        }
        for (Position point : back == null ? List.of(p.home()) : back) {
            steps.add(new Step(0, "Reseteando: regreso a HOME", point, p.value("HOME_TIME"), null));
        }
        steps.add(
                new Step(0, "Reseteando: apertura en HOME", null, p.value("GRIPPER_OPEN_TIME"), 0));
        return List.copyOf(steps);
    }
}
