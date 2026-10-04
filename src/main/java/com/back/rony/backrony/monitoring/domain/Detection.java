package com.back.rony.backrony.monitoring.domain;

/** Observed residue. Projected coordinates can be absent when calibration is invalid. */
public record Detection(
        String residue,
        String shortName,
        String category,
        double confidence,
        Point pixel,
        Point offsetMm,
        Point targetMm,
        String destination) {
    public Detection {
        if (residue == null
                || residue.length() > 160
                || shortName == null
                || shortName.length() > 40
                || !java.util.List.of("plastic", "cardboard", "aluminum").contains(category)
                || !Double.isFinite(confidence)
                || confidence < 0
                || confidence > 1
                || pixel == null
                || destination == null
                || destination.length() > 160) {
            throw new IllegalArgumentException("Detección inválida.");
        }
    }

    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || Math.abs(x) > 1e6
                    || Math.abs(y) > 1e6) {
                throw new IllegalArgumentException("Coordenadas inválidas.");
            }
        }
    }

    public Detection withDestination(String value) {
        return new Detection(
                residue, shortName, category, confidence, pixel, offsetMm, targetMm, value);
    }

    public static Detection demo(long index) {
        String[] categories = {"plastic", "cardboard", "aluminum"};
        String[] labels = {"Plástico", "Cartón", "Aluminio"};
        int i = (int) (index % 3);
        return new Detection(
                labels[i],
                labels[i],
                categories[i],
                .91,
                new Point(320, 240),
                new Point(12, 20),
                new Point(12, -142.94),
                "Contenedor de " + labels[i].toLowerCase(java.util.Locale.ROOT) + " (virtual)");
    }
}
