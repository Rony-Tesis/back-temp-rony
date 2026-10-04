package com.back.rony.backrony.robotics.domain;

/** Controller-reported setpoint, not encoder-confirmed physical arrival. */
public record RobotPose(double x, double y, double z, double gripperAngle) {
    public RobotPose {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Double.isFinite(gripperAngle)
                || gripperAngle < 0
                || gripperAngle > 180) {
            throw new IllegalArgumentException("Telemetría inválida.");
        }
    }

    public boolean near(Position p) {
        return Math.abs(x - p.x()) <= 2 && Math.abs(y - p.y()) <= 2 && Math.abs(z - p.z()) <= 2;
    }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || x < -100
                    || x > 100
                    || y < -240
                    || y > -90
                    || z < 90
                    || z > 213
                    || Math.hypot(x, y) < 50) {
                throw new IllegalArgumentException(
                        "Objetivo fuera de la zona calibrada de prueba.");
            }
        }
    }
}
