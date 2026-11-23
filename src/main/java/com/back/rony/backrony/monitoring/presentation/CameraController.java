package com.back.rony.backrony.monitoring.presentation;

import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import com.back.rony.backrony.security.application.CameraAccessPolicy;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Browser reads video directly from Render, bypassing the buffering SPA proxy. */
@RestController
public final class CameraController {
    private final MonitorPort monitor;
    private final CameraAccessPolicy access;
    private final Semaphore streams;
    private final String publicUrl;

    public CameraController(
            MonitorPort monitor,
            CameraAccessPolicy access,
            RonyProperties properties,
            @Value("${rony.camera-public-url:}") String publicUrl) {
        this.monitor = monitor;
        this.access = access;
        this.streams = new Semaphore(properties.maximumStreams());
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        if (!this.publicUrl.isEmpty()) {
            URI url = URI.create(this.publicUrl);
            if (!"https".equals(url.getScheme())
                    || url.getHost() == null
                    || url.getUserInfo() != null
                    || url.getQuery() != null
                    || url.getFragment() != null
                    || !url.getPath().isEmpty()) {
                throw new IllegalArgumentException("La cámara pública requiere un origen HTTPS.");
            }
        }
    }

    // Protected by the normal HttpOnly web session. No general access key leaves that session.
    @GetMapping("/api/v1/monitor/camera-access")
    public ResponseEntity<?> access() {
        if (publicUrl.isEmpty()) return ResponseEntity.status(503).build();
        return ResponseEntity.ok(
                Map.of(
                        "url",
                        publicUrl + "/api/v1/monitor/camera-stream",
                        "token",
                        access.issue(),
                        "expiresInSeconds",
                        30));
    }

    @CrossOrigin(
            origins = "${rony.frontend-url:http://localhost:4200}",
            methods = RequestMethod.GET,
            allowedHeaders = "Authorization",
            maxAge = 600)
    @GetMapping(value = "/api/v1/monitor/camera-stream", produces = "multipart/x-mixed-replace")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!access.consume(authorization)) return ResponseEntity.status(401).build();
        if (monitor.frame().jpeg() == null)
            return ResponseEntity.status(503).header("Retry-After", "2").build();
        if (!streams.tryAcquire())
            return ResponseEntity.status(429).header("Retry-After", "2").build();
        StreamingResponseBody body =
                out -> {
                    long sequence = -1;
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                    try {
                        while (monitor.running() && System.nanoTime() < deadline) {
                            MonitorPort.Frame frame = monitor.waitFrame(sequence);
                            if (frame.jpeg() == null) break;
                            if (frame.sequence() == sequence) continue;
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
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (java.io.IOException disconnected) {
                        // Browser navigation or network loss; release the slot without another
                        // response.
                    } finally {
                        streams.release();
                    }
                };
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Content-Type", "multipart/x-mixed-replace; boundary=rony-frame")
                .body(body);
    }
}
