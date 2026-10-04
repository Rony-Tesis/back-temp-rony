package com.back.rony.backrony.monitoring.application.port;

import com.back.rony.backrony.monitoring.domain.Detection;
import java.util.function.Consumer;

/** One capture/inference source feeds every API client. */
public interface VisionPort extends AutoCloseable {
    void start(Consumer<Observation> observed, Consumer<String> failed);

    @Override
    void close();

    record Observation(
            byte[] jpeg, Detection detection, int stableFrames, Detection targetDetection) {
        public Observation {
            if (jpeg == null
                    || jpeg.length == 0
                    || jpeg.length > 8 * 1024 * 1024
                    || stableFrames < 0) {
                throw new IllegalArgumentException("Observación inválida.");
            }
            jpeg = jpeg.clone();
        }

        @Override
        public byte[] jpeg() {
            return jpeg.clone();
        }
    }
}
