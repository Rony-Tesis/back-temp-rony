package com.back.rony.backrony.shared.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** External, validated configuration. Never contains browser-supplied worker commands. */
@Validated
@ConfigurationProperties("rony")
public record RonyProperties(
        @Pattern(regexp = "simulation|live") String sourceMode,
        boolean physicalMotion,
        boolean workersEnabled,
        @Min(1) @Max(30) int framesPerSecond,
        String accessToken,
        @NotEmpty List<String> allowedHosts,
        @Min(1) @Max(32) int maximumStreams,
        @NotNull Path pythonExecutable,
        @NotNull Path workerScript,
        @NotNull URI cameraUrl,
        @NotNull Path modelPath,
        @NotNull Path credentialsNotebook) {
    public RonyProperties {
        if (physicalMotion && !"live".equals(sourceMode)) {
            throw new IllegalArgumentException("El movimiento físico requiere cámara real.");
        }
        if (accessToken != null
                && !accessToken.isEmpty()
                && (accessToken.length() < 24 || accessToken.length() > 256)) {
            throw new IllegalArgumentException("El token debe tener entre 24 y 256 caracteres.");
        }
        if (cameraUrl != null
                && (!List.of("http", "https").contains(cameraUrl.getScheme())
                        || cameraUrl.getHost() == null
                        || cameraUrl.getUserInfo() != null)) {
            throw new IllegalArgumentException(
                    "La cámara requiere una URL HTTP(S) sin credenciales.");
        }
    }
}
