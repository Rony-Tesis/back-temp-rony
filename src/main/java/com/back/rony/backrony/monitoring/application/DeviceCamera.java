package com.back.rony.backrony.monitoring.application;

import com.back.rony.backrony.monitoring.application.port.MonitorPort.Frame;
import java.util.concurrent.TimeUnit;

/** One latest image, independent of database/telemetry locks. Video never renews control health. */
final class DeviceCamera {
    private String boot, uploader;
    private byte[] jpeg;
    private long telemetryAt, imageAt, videoAt, sourceSequence, sequence;
    private boolean online;

    synchronized void telemetry(String nextBoot, boolean cameraOnline, byte[] fallback) {
        if (!nextBoot.equals(boot)) {
            boot = nextBoot;
            uploader = null;
            jpeg = null;
            videoAt = 0;
            sourceSequence = -1;
        }
        telemetryAt = System.nanoTime();
        online = cameraOnline;
        // Old runtimes keep working. Do not replace fresh continuous video with a slower snapshot.
        if (fallback != null && telemetryAt - videoAt > TimeUnit.SECONDS.toNanos(2)) {
            publish(fallback);
        }
        notifyAll();
    }

    synchronized boolean open(String nextBoot, String channel) {
        if (!fresh() || !nextBoot.equals(boot)) return false;
        uploader = channel;
        sourceSequence = -1;
        return true;
    }

    synchronized boolean receive(String nextBoot, String channel, long source, byte[] bytes) {
        if (!fresh()
                || !nextBoot.equals(boot)
                || !channel.equals(uploader)
                || source <= sourceSequence) return false;
        validate(bytes);
        sourceSequence = source;
        videoAt = System.nanoTime();
        publish(bytes);
        return true;
    }

    synchronized void disconnect(String channel) {
        if (channel.equals(uploader)) uploader = null;
    }

    static void validate(byte[] bytes) {
        if (bytes.length < 4
                || bytes.length > 300000
                || bytes[0] != (byte) 0xff
                || bytes[1] != (byte) 0xd8
                || bytes[bytes.length - 2] != (byte) 0xff
                || bytes[bytes.length - 1] != (byte) 0xd9) {
            throw new IllegalArgumentException("JPEG inválido.");
        }
    }

    private void publish(byte[] bytes) {
        jpeg = bytes;
        imageAt = System.nanoTime();
        sequence++;
        notifyAll();
    }

    private boolean fresh() {
        return boot != null && System.nanoTime() - telemetryAt < TimeUnit.SECONDS.toNanos(6);
    }

    synchronized Frame frame() {
        return new Frame(
                sequence,
                fresh() && online && System.nanoTime() - imageAt < TimeUnit.SECONDS.toNanos(3)
                        ? jpeg
                        : null);
    }

    synchronized Frame waitFrame(long after) throws InterruptedException {
        if (sequence <= after) wait(1000);
        return frame();
    }

    synchronized void close() {
        online = false;
        jpeg = null;
        notifyAll();
    }
}
