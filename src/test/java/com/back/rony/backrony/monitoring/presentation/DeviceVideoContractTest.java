package com.back.rony.backrony.monitoring.presentation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.back.rony.backrony.monitoring.application.CloudDeviceRelay;
import com.back.rony.backrony.monitoring.application.CloudDeviceRelayTest;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rony.execution-location=cloud",
            "rony.access-token=video-web-test-token-0123456789012345",
            "rony.device-token=video-device-test-token-0123456789012345"
        })
@ActiveProfiles("test")
class DeviceVideoContractTest {
    @LocalServerPort int port;
    @Autowired MonitorPort monitor;
    private static final String DEVICE = "Bearer video-device-test-token-0123456789012345";

    WebSocket.Builder builder(String authorization, String boot) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", authorization)
                .header("X-Rony-Boot-Id", boot);
    }

    URI uri() {
        return URI.create("ws://localhost:" + port + "/api/v1/device/video");
    }

    @Test
    void continuousFramesAreIndependentOfTelemetryAndCannotCreateCommands() throws Exception {
        var relay = (CloudDeviceRelay) monitor;
        String boot = UUID.randomUUID().toString();
        relay.exchange(CloudDeviceRelayTest.packet(boot, 1, List.of(), List.of()));
        var replies = new LinkedBlockingQueue<String>();
        var listener =
                new WebSocket.Listener() {
                    public void onOpen(WebSocket socket) {
                        socket.request(1);
                    }

                    public CompletionStage<?> onText(
                            WebSocket socket, CharSequence data, boolean last) {
                        replies.add(data.toString());
                        socket.request(1);
                        return null;
                    }
                };
        WebSocket socket =
                builder(DEVICE, boot).buildAsync(uri(), listener).get(5, TimeUnit.SECONDS);
        try {
            for (int i = 1; i <= 20; i++) {
                byte[] jpeg = {(byte) 255, (byte) 216, (byte) i, (byte) 255, (byte) 217};
                socket.sendBinary(ByteBuffer.allocate(13).putLong(i).put(jpeg).flip(), true).join();
                assertEquals(Integer.toString(i), replies.poll(2, TimeUnit.SECONDS));
                assertArrayEquals(jpeg, relay.frame().jpeg());
            }
            assertEquals(
                    List.of(),
                    relay.exchange(CloudDeviceRelayTest.packet(boot, 2, List.of(), List.of()))
                            .commands());
        } finally {
            socket.abort();
        }
    }

    @Test
    void upgradeRejectsBrowserCredentialsAndOriginsBeforeAcceptingImages() {
        String boot = UUID.randomUUID().toString();
        var invalid =
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () ->
                                builder("Bearer video-web-test-token-0123456789012345", boot)
                                        .buildAsync(uri(), new WebSocket.Listener() {})
                                        .join());
        assertEquals(
                401, ((WebSocketHandshakeException) invalid.getCause()).getResponse().statusCode());
        var browser =
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () ->
                                builder(DEVICE, boot)
                                        .header("Origin", "http://localhost")
                                        .buildAsync(uri(), new WebSocket.Listener() {})
                                        .join());
        assertEquals(
                401, ((WebSocketHandshakeException) browser.getCause()).getResponse().statusCode());
    }
}
