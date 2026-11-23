package com.back.rony.backrony.security.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CameraAccessPolicyTest {
    @Test
    void capabilitiesAreSingleUseExpiringAndBounded() {
        var clock = new AtomicLong(1);
        var policy = new CameraAccessPolicy(clock::get);
        var token = policy.issue();
        assertFalse(policy.consume("Bearer " + token));
        assertTrue(policy.consume("Camera " + token));
        assertFalse(policy.consume("Camera " + token));
        var expired = policy.issue();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertFalse(policy.consume("Camera " + expired));
        var evicted = policy.issue();
        for (int i = 0; i < 64; i++) policy.issue();
        assertFalse(policy.consume("Camera " + evicted));
    }
}
