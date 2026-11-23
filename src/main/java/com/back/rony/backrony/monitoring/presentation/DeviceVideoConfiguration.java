package com.back.rony.backrony.monitoring.presentation;

import com.back.rony.backrony.monitoring.application.CloudDeviceRelay;
import com.back.rony.backrony.monitoring.application.port.MonitorPort;
import com.back.rony.backrony.security.application.DeviceAccessPolicy;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Device-only outbound WSS upload. Browser capabilities and robot controls are separate. */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "rony.execution-location", havingValue = "cloud")
public class DeviceVideoConfiguration implements WebSocketConfigurer {
    private final CloudDeviceRelay relay;
    private final DeviceAccessPolicy access;

    public DeviceVideoConfiguration(MonitorPort monitor, DeviceAccessPolicy access) {
        relay = (CloudDeviceRelay) monitor;
        this.access = access;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Upload(), "/api/v1/device/video")
                .addInterceptors(
                        new HandshakeInterceptor() {
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Map<String, Object> attributes) {
                                String boot = request.getHeaders().getFirst("X-Rony-Boot-Id");
                                if (request.getURI().getRawQuery() != null
                                        || request.getHeaders().getOrigin() != null
                                        || !access.matches(
                                                request.getHeaders().getFirst("Authorization"))) {
                                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                                    return false;
                                }
                                try {
                                    if (!UUID.fromString(boot).toString().equals(boot))
                                        throw new IllegalArgumentException();
                                } catch (RuntimeException invalid) {
                                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                                    return false;
                                }
                                // Reject before HTTP 101 so proxies do not keep an unattached
                                // stream.
                                if (!relay.acceptsVideoBoot(boot)) {
                                    response.setStatusCode(HttpStatus.CONFLICT);
                                    return false;
                                }
                                attributes.put("boot", boot);
                                return true;
                            }

                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Exception exception) {}
                        });
    }

    private final class Upload extends BinaryWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            session.setBinaryMessageSizeLimit(300008);
            session.setTextMessageSizeLimit(128);
            if (!relay.openVideo((String) session.getAttributes().get("boot"), session.getId())) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
                throws Exception {
            ByteBuffer payload = message.getPayload();
            if (payload.remaining() < 12 || payload.remaining() > 300008) {
                session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                return;
            }
            long source = payload.getLong();
            byte[] jpeg = new byte[payload.remaining()];
            payload.get(jpeg);
            try {
                if (!relay.videoFrame(
                        (String) session.getAttributes().get("boot"),
                        session.getId(),
                        source,
                        jpeg)) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
            } catch (IllegalArgumentException invalid) {
                session.close(CloseStatus.BAD_DATA);
                return;
            }
            // Bounded acknowledgements let the sender drop old camera frames instead of queuing
            // video.
            session.sendMessage(new TextMessage(Long.toString(source)));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            relay.closeVideo(session.getId());
        }
    }
}
