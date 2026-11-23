package com.back.rony.backrony.robotics.domain;

/** Controller-reported setpoint, not encoder-confirmed physical arrival. */
public record RobotPose(double x, double y, double z, double gripperAngle, JointAngles joints) {
    public RobotPose(double x, double y, double z, double gripperAngle) {
        this(x, y, z, gripperAngle, null);
    }

    public record JointAngles(double joint1, double joint2, double joint3) {
        public JointAngles {
            if (!Double.isFinite(joint1) || !Double.isFinite(joint2) || !Double.isFinite(joint3)) {
                throw new IllegalArgumentException("Telemetría articular inválida.");
            }
        }
    }

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
