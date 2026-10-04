package com.back.rony.backrony.monitoring.presentation;

import com.back.rony.backrony.monitoring.application.MonitorService;
import com.back.rony.backrony.monitoring.application.MonitorSnapshot;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/monitor")
@SecurityRequirement(name = "bearerToken")
@SecurityRequirement(name = "sessionCookie")
public class MonitorController {
    private final MonitorService monitor;
    private final ObjectMapper mapper;
    private final Semaphore streams;

    public MonitorController(
            MonitorService monitor, ObjectMapper mapper, RonyProperties properties) {
        this.monitor = monitor;
        this.mapper = mapper;
        streams = new Semaphore(properties.maximumStreams());
    }

    @GetMapping("/snapshot")
    @Operation(summary = "Consultar el snapshot coherente de cámara y proceso")
    public MonitorSnapshot snapshot() {
        return monitor.snapshot();
    }

    @GetMapping("/status")
    @Operation(summary = "Consultar disponibilidad, modos y estado del JetMax")
    public Map<String, Object> status() {
        return monitor.status();
    }

    @GetMapping(value = "/frame", produces = "image/jpeg")
    @Operation(summary = "Obtener el último JPEG procesado")
    @ApiResponse(responseCode = "503", description = "Sin imagen disponible; Retry-After: 2")
    public ResponseEntity<byte[]> frame() {
        byte[] jpeg = monitor.frame().jpeg();
        return jpeg == null
                ? ResponseEntity.status(503).header("Retry-After", "2").build()
                : ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    @GetMapping(value = "/events", produces = "text/event-stream")
    @Operation(summary = "Suscribirse a snapshots SSE aproximadamente cada 100 ms")
    @ApiResponse(responseCode = "429", description = "Máximo de conexiones de streaming alcanzado")
    public ResponseEntity<StreamingResponseBody> events() {
        if (!streams.tryAcquire())
            return ResponseEntity.status(429).header("Retry-After", "2").build();
        StreamingResponseBody body =
                out -> {
                    try {
                        while (monitor.running()) {
                            out.write(
                                    ("data: "
                                                    + mapper.writeValueAsString(monitor.snapshot())
                                                    + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (java.io.IOException disconnected) {
                        // The client closed its stream. Do not write a second HTTP response.
                    } finally {
                        streams.release();
                    }
                };
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    @GetMapping(value = "/stream", produces = "multipart/x-mixed-replace")
    @Operation(summary = "Ver MJPEG compartido sin crear inferencias por cliente")
    @ApiResponses({
        @ApiResponse(responseCode = "503", description = "Cámara no disponible"),
        @ApiResponse(
                responseCode = "429",
                description = "Máximo de conexiones de streaming alcanzado")
    })
    public ResponseEntity<StreamingResponseBody> stream() {
        if (monitor.frame().jpeg() == null)
            return ResponseEntity.status(503).header("Retry-After", "2").build();
        if (!streams.tryAcquire())
            return ResponseEntity.status(429).header("Retry-After", "2").build();
        StreamingResponseBody body =
                out -> {
                    long sequence = -1;
                    try {
                        while (monitor.running()) {
                            MonitorService.Frame frame;
                            try {
                                frame = monitor.waitFrame(sequence);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            if (frame.jpeg() == null) break;
                            if (sequence == frame.sequence()) continue;
                            sequence = frame.sequence();
                            out.write(
                                    ("--rony-frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                                    + frame.jpeg().length
                                                    + "\r\n\r\n")
                                            .getBytes(StandardCharsets.US_ASCII));
                            out.write(frame.jpeg());
                            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        }
                    } catch (java.io.IOException disconnected) {
                        // The client closed its stream. Do not write a second HTTP response.
                    } finally {
                        streams.release();
                    }
                };
        return ResponseEntity.ok()
                .header("Content-Type", "multipart/x-mixed-replace; boundary=rony-frame")
                .body(body);
    }
}
