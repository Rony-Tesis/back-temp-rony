package com.back.rony.backrony.monitoring.application.port;

import com.back.rony.backrony.monitoring.application.MonitorSnapshot;
import java.util.Map;

/** Browser-facing monitor, backed by local bench processing or a remote autonomous robot. */
public interface MonitorPort extends AutoCloseable {
    void start();

    MonitorSnapshot snapshot();

    Map<String, Object> status();

    Frame frame();

    Frame waitFrame(long after) throws InterruptedException;

    boolean running();

    void physicalCommand(String action);

    void virtualCommand(String action);

    void close();

    record Frame(long sequence, byte[] jpeg) {}
}
