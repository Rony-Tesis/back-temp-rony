package com.back.rony.backrony.shared.infrastructure;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local JSON-lines transport. Fixed executable/script, separate vision and robot processes. */
public final class PythonWorkerProcess implements AutoCloseable {
    private final RonyProperties properties;
    private final ObjectMapper mapper;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private volatile Process process;
    private BufferedWriter input;
    private volatile boolean closed;

    public PythonWorkerProcess(RonyProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void start(String kind, Consumer<JsonNode> events, Runnable failed) {
        if (!java.util.List.of("vision", "robot").contains(kind))
            throw new IllegalArgumentException("Worker inválido.");
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(
                            properties.pythonExecutable().toAbsolutePath().normalize().toString(),
                            "-u",
                            properties.workerScript().toAbsolutePath().normalize().toString(),
                            "--kind",
                            kind);
            builder.environment().put("RONY_CAMERA_URL", properties.cameraUrl().toString());
            builder.environment()
                    .put(
                            "RONY_MODEL_PATH",
                            properties.modelPath().toAbsolutePath().normalize().toString());
            builder.environment()
                    .put(
                            "RONY_CREDENTIALS_NOTEBOOK",
                            properties
                                    .credentialsNotebook()
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString());
            builder.environment().put("RONY_FPS", Integer.toString(properties.framesPerSecond()));
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().remove("RONY_DB_PASSWORD");
            builder.environment().remove("RONY_ACCESS_TOKEN");
            process = builder.start();
            input =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual()
                    .name("rony-worker-stderr")
                    .start(
                            () -> {
                                try (InputStream errors = process.getErrorStream()) {
                                    errors.transferTo(OutputStream.nullOutputStream());
                                } catch (IOException ignored) {
                                    /* Worker errors are sanitized in the public status. */
                                }
                            });
            Thread.ofVirtual()
                    .name("rony-" + kind + "-reader")
                    .start(
                            () -> {
                                try (BufferedReader reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        process.getInputStream(),
                                                        StandardCharsets.UTF_8))) {
                                    String line;
                                    while (!closed && (line = reader.readLine()) != null) {
                                        if (line.length() > 12 * 1024 * 1024)
                                            throw new IOException("Worker frame too large");
                                        JsonNode value = mapper.readTree(line);
                                        if ("reply".equals(value.path("kind").asString())) {
                                            CompletableFuture<JsonNode> request =
                                                    pending.remove(value.path("id").asLong());
                                            if (request != null) request.complete(value);
                                        } else events.accept(value);
                                    }
                                } catch (Exception ignored) {
                                    /* Never log credentials, notebook content or frames. */
                                } finally {
                                    pending.values()
                                            .forEach(
                                                    f ->
                                                            f.completeExceptionally(
                                                                    new IllegalStateException(
                                                                            "Worker desconectado.")));
                                    pending.clear();
                                    if (!closed) failed.run();
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo iniciar el adaptador local.");
        }
    }

    public JsonNode request(Map<String, Object> payload) {
        if (closed || process == null || !process.isAlive())
            throw new IllegalStateException("Adaptador no disponible.");
        long id = sequence.incrementAndGet();
        Map<String, Object> value = new java.util.LinkedHashMap<>(payload);
        value.put("id", id);
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(id, reply);
        try {
            synchronized (this) {
                input.write(mapper.writeValueAsString(value));
                input.newLine();
                input.flush();
            }
            JsonNode response = reply.get(12, TimeUnit.SECONDS);
            if (!response.path("ok").asBoolean())
                throw new IllegalStateException("El adaptador ROS rechazó la operación.");
            return response;
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("El adaptador ROS no respondió.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void close() {
        closed = true;
        Process current = process;
        if (current != null) {
            try {
                current.getOutputStream().close();
            } catch (IOException ignored) {
            }
            current.destroy();
        }
        pending.values()
                .forEach(
                        f ->
                                f.completeExceptionally(
                                        new IllegalStateException("Adaptador cerrado.")));
        pending.clear();
    }
}
