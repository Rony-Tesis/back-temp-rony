package com.back.rony.backrony.monitoring.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeviceCameraTest {
    private static final byte[] SNAPSHOT = {(byte) 255, (byte) 216, 1, (byte) 255, (byte) 217};
    private static final byte[] VIDEO = {(byte) 255, (byte) 216, 2, (byte) 255, (byte) 217};

    @Test
    void videoOutrunsTelemetryAndSlowerSnapshotsCannotRewindIt() throws Exception {
        var camera = new DeviceCamera();
        camera.telemetry("boot-1", true, SNAPSHOT);
        assertTrue(camera.open("boot-1", "video-1"));
        long after = camera.frame().sequence();
        for (int i = 1; i <= 40; i++) assertTrue(camera.receive("boot-1", "video-1", i, VIDEO));
        camera.telemetry("boot-1", true, SNAPSHOT);
        assertEquals(after + 40, camera.frame().sequence());
        assertArrayEquals(VIDEO, camera.waitFrame(after).jpeg());
        camera.telemetry("boot-1", true, null);
        assertArrayEquals(VIDEO, camera.frame().jpeg());
    }

    @Test
    void restartRetiresTheOldUploaderAndOldFrames() {
        var camera = new DeviceCamera();
        assertFalse(camera.open("boot-1", "unregistered"));
        camera.telemetry("boot-1", true, SNAPSHOT);
        assertTrue(camera.open("boot-1", "video-1"));
        assertTrue(camera.receive("boot-1", "video-1", 5, VIDEO));
        assertFalse(camera.receive("boot-1", "video-1", 4, SNAPSHOT));
        camera.telemetry("boot-2", true, null);
        assertNull(camera.frame().jpeg());
        assertFalse(camera.receive("boot-1", "video-1", 6, VIDEO));
        assertTrue(camera.open("boot-2", "video-2"));
        assertTrue(camera.receive("boot-2", "video-2", 1, VIDEO));
        camera.disconnect("video-1");
        assertTrue(camera.receive("boot-2", "video-2", 2, VIDEO));
        camera.close();
        assertNull(camera.frame().jpeg());
    }

    @Test
    void rejectsMalformedImagesWithoutChangingTheLastGoodFrame() {
        var camera = new DeviceCamera();
        camera.telemetry("boot-1", true, SNAPSHOT);
        camera.open("boot-1", "video-1");
        assertThrows(
                IllegalArgumentException.class,
                () -> camera.receive("boot-1", "video-1", 1, new byte[300001]));
        assertArrayEquals(SNAPSHOT, camera.frame().jpeg());
    }
}
