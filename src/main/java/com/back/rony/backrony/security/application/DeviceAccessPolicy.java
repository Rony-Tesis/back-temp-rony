package com.back.rony.backrony.security.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Robot credentials are separate from browser sessions. */
@Component
public final class DeviceAccessPolicy {
    private final String token;

    public DeviceAccessPolicy(
            @Value("${rony.device-token:}") String token,
            @Value("${rony.execution-location:pc}") String location) {
        if ("cloud".equals(location)
                && (token == null || token.length() < 32 || token.length() > 256)) {
            throw new IllegalArgumentException("Configura RONY_DEVICE_TOKEN para el JetMax.");
        }
        this.token = token;
    }

    public boolean matches(String header) {
        if (token == null || token.length() < 32 || header == null || !header.startsWith("Bearer "))
            return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                header.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
