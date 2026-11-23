package com.back.rony.backrony.robotics.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
import com.back.rony.backrony.robotics.domain.ManualControls;
import com.back.rony.backrony.robotics.domain.MotionPlan;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class HttpRobotAdapterTest {
    @TempDir Path temporary;
    private static final String TOKEN = "test-agent-token-012345678901234567890";

    @Test
    void refusesAnOlderAgentWithoutVerticalHomePolicy() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/state",
                exchange -> {
                    byte[] body = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try (var adapter =
                new HttpRobotAdapter(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        TOKEN,
                        TestProfile.profile(),
                        JsonMapper.builder().build())) {
            var error = assertThrows(IllegalStateException.class, () -> adapter.connect(p -> {}));
            assertTrue(error.getMessage().contains("elevación vertical y HOME"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesTheAgentReasonWithoutExposingUnstructuredResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/calibration",
                exchange -> {
                    byte[] body =
                            "{\"detail\":\"Calibración del brazo no disponible\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try (var adapter =
                new HttpRobotAdapter(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        TOKEN,
                        TestProfile.profile(),
                        JsonMapper.builder().build())) {
            var error = assertThrows(IllegalStateException.class, adapter::readCalibration);
            assertTrue(error.getMessage().contains("HTTP 409"));
            assertTrue(error.getMessage().contains("Calibración del brazo no disponible"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsACompleteTrajectoryToRealPythonAgentWithoutSsh() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        String python =
                System.getenv()
                        .getOrDefault(
                                "RONY_TEST_PYTHON",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "python"
                                        : "python3");
        ProcessBuilder builder =
                new ProcessBuilder(
                        python,
                        "robot_agent/agent.py",
                        "--fake",
                        "--profile",
                        "../rony-yolo-isaac/runtime/rony_profile.json",
                        "--port",
                        Integer.toString(port));
        builder.environment().put("RONY_AGENT_TOKEN", TOKEN);
        builder.redirectErrorStream(true).redirectOutput(temporary.resolve("agent.log").toFile());
        Process process = builder.start();
        URI endpoint = URI.create("http://127.0.0.1:" + port);
        var profile = TestProfile.profile();
        var mapper = JsonMapper.builder().build();
        try {
            boolean ready = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!ready && process.isAlive() && System.nanoTime() < deadline) {
                try (var probe = new HttpRobotAdapter(endpoint, TOKEN, profile, mapper, true)) {
                    probe.connect(p -> {});
                    ready = true;
                } catch (IllegalStateException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "Python fake agent did not start");
            try (var denied =
                    new HttpRobotAdapter(
                            endpoint,
                            "incorrect-token-012345678901234567890",
                            profile,
                            mapper,
                            true)) {
                assertThrows(IllegalStateException.class, () -> denied.connect(p -> {}));
            }
            try (var physical = new HttpRobotAdapter(endpoint, TOKEN, profile, mapper)) {
                assertThrows(IllegalStateException.class, () -> physical.connect(p -> {}));
            }
            try (var adapter = new HttpRobotAdapter(endpoint, TOKEN, profile, mapper, true)) {
                adapter.connect(p -> {});
                var calibration = adapter.readCalibration();
                assertEquals(adapter.calibrationSha256(), calibration.path("sha256").asString());
                assertTrue(calibration.path("parameters").path("K").isArray());
                adapter.preflight(List.of(profile.home()));
                assertEquals(0, adapter.currentPose().gripperAngle());
                List<MotionPlan.Step> done = new ArrayList<>();
                for (String category : List.of("plastic", "aluminum", "cardboard")) {
                    var plan = MotionPlan.sort(TestProfile.target(category), profile);
                    var result =
                            adapter.executePlan(
                                    plan, adapter.currentPose(), () -> {}, s -> {}, done::add);
                    assertTrue(result.near(profile.home()));
                    assertEquals(0, result.gripperAngle());
                }
                assertEquals(44, done.size());
                assertFalse(
                        done.stream().anyMatch(step -> Integer.valueOf(82).equals(step.gripper())));
                var closed =
                        adapter.executeManual(
                                "close-gripper",
                                adapter.currentPose(),
                                profile,
                                () -> {},
                                s -> {},
                                s -> {});
                assertEquals(90, closed.gripperAngle());
                var parked =
                        adapter.executeManual(
                                "park", adapter.currentPose(), profile, () -> {}, s -> {}, s -> {});
                assertTrue(parked.near(ManualControls.storage()));
                var restored =
                        adapter.executePlan(
                                MotionPlan.reset(parked, profile),
                                parked,
                                () -> {},
                                s -> {},
                                s -> {});
                assertTrue(restored.near(profile.home()));
                assertEquals(0, restored.gripperAngle());
            }
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }
}
