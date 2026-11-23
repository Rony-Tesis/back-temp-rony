package com.back.rony.backrony.security.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** One-use, short-lived capabilities for camera viewing, never robot control. */
@Service
public final class CameraAccessPolicy {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> tickets = new LinkedHashMap<>();
    private final LongSupplier clock;

    public CameraAccessPolicy() {
        this(System::nanoTime);
    }

    CameraAccessPolicy(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized String issue() {
        long now = clock.getAsLong();
        tickets.values().removeIf(expiry -> expiry <= now);
        while (tickets.size() >= 64) tickets.remove(tickets.keySet().iterator().next());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, now + TimeUnit.SECONDS.toNanos(30));
        return ticket;
    }

    public synchronized boolean consume(String authorization) {
        if (authorization == null || !authorization.matches("Camera [A-Za-z0-9_-]{43}"))
            return false;
        Long expiry = tickets.remove(authorization.substring(7));
        return expiry != null && expiry > clock.getAsLong();
    }
}
