package com.back.rony.backrony.monitoring.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VirtualCycleTest {
    @Test
    void completesOnceWithOpenGripperAndNoRepeatedOperation() {
        VirtualCycle cycle = new VirtualCycle();
        cycle.start(Detection.demo(0));
        var result = cycle.advance(15900, Instant.EPOCH);
        assertNotNull(result);
        assertEquals(15900, result.durationMs());
        assertTrue(cycle.process().completed());
        assertFalse(cycle.process().gripperClosed());
        assertEquals(100, cycle.process().progressPercent());
        assertNull(cycle.advance(100, Instant.EPOCH));
    }

    @Test
    void pauseDoesNotAdvanceAndConcurrentStartIsRejected() {
        VirtualCycle cycle = new VirtualCycle();
        cycle.start(Detection.demo(0));
        cycle.paused(true);
        assertNull(cycle.advance(16000, Instant.EPOCH));
        assertEquals(0, cycle.process().elapsedMs());
        assertThrows(IllegalStateException.class, () -> cycle.start(Detection.demo(1)));
    }

    @Test
    void invalidCoordinatesAndConfidenceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Detection.Point(Double.NaN, 1));
        var d = Detection.demo(0);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Detection(
                                d.residue(),
                                d.shortName(),
                                "plastic",
                                2,
                                d.pixel(),
                                d.offsetMm(),
                                d.targetMm(),
                                d.destination()));
    }
}
