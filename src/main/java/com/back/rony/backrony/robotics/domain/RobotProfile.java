package com.back.rony.backrony.robotics.domain;

import java.util.List;
import java.util.Map;

/** Immutable values exported from the current movement notebook. Millimetres and seconds. */
public record RobotProfile(
        Map<String, Double> values,
        Map<String, Destination> bins,
        List<String> enabledCategories,
        Workspace pickupWorkspace,
        boolean provisionalDestinations,
        String sha256) {
    public RobotProfile {
        values = Map.copyOf(values);
        bins = Map.copyOf(bins);
        enabledCategories = List.copyOf(enabledCategories);
        if (!bins.keySet().containsAll(enabledCategories) || enabledCategories.isEmpty()) {
            throw new IllegalArgumentException("Las clases habilitadas requieren un destino.");
        }
        if (values.values().stream().anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException("Perfil con valores no finitos.");
        }
    }

    public double value(String name) {
        Double value = values.get(name);
        if (value == null)
            throw new IllegalArgumentException("Falta el valor del notebook: " + name);
        return value;
    }

    public int integer(String name) {
        return Math.toIntExact(Math.round(value(name)));
    }

    public RobotPose.Position home() {
        return new RobotPose.Position(value("HOME_X"), value("HOME_Y"), value("HOME_Z"));
    }

    public Destination destination(String category) {
        Destination destination = bins.get(category);
        if (!enabledCategories.contains(category) || destination == null) {
            throw new IllegalArgumentException("Clase no habilitada para recogida.");
        }
        return destination;
    }

    public record Workspace(
            double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        public boolean contains(double x, double y, double z) {
            return Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(z)
                    && x >= minX
                    && x <= maxX
                    && y >= minY
                    && y <= maxY
                    && z >= minZ
                    && z <= maxZ;
        }
    }

    public record Destination(
            String label,
            RobotPose.Position position,
            double baseAngleDeg,
            List<RobotPose.Position> transitMm) {
        public Destination {
            transitMm = List.copyOf(transitMm);
            if (transitMm.isEmpty()
                    || !transitMm.getLast().equals(position)
                    || baseAngleDeg < 10
                    || baseAngleDeg > 230) {
                throw new IllegalArgumentException("Destino sin recorrido de giro válido.");
            }
        }
    }
}
