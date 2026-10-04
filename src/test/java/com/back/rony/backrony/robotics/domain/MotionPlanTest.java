package com.back.rony.backrony.robotics.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.back.rony.backrony.monitoring.domain.Detection;
import org.junit.jupiter.api.Test;

class MotionPlanTest {
    @Test
    void graspKeeps82DegreesAndReturnsHomeWithoutOpening() {
        var steps = MotionPlan.grasp(Detection.demo(0));
        assertEquals(5, steps.size());
        assertEquals(82, steps.get(2).gripper());
        assertEquals(MotionPlan.HOME, steps.getLast().position());
        assertNull(steps.getLast().gripper());
    }

    @Test
    void resetLiftsBeforeHomeThenOpens() {
        var steps = MotionPlan.reset(new RobotPose(12, -142.94, 90, 82));
        assertEquals(120, steps.getFirst().position().z());
        assertEquals(MotionPlan.HOME, steps.get(1).position());
        assertEquals(0, steps.getLast().gripper());
    }

    @Test
    void highResetDoesNotIntroduceExtraLift() {
        assertEquals(2, MotionPlan.reset(new RobotPose(0, -162.94, 212.8, 82)).size());
    }

    @Test
    void nonPlasticLowConfidenceAndUncalibratedTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MotionPlan.grasp(Detection.demo(1)));
        Detection d = Detection.demo(0);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.grasp(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .79,
                                        d.pixel(),
                                        d.offsetMm(),
                                        d.targetMm(),
                                        d.destination())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.grasp(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .91,
                                        d.pixel(),
                                        null,
                                        null,
                                        d.destination())));
    }

    @Test
    void allWorkspaceAxesRejectOutOfBoundsAndNonFiniteValues() {
        for (double[] p :
                new double[][] {
                    {101, -150, 120},
                    {0, -241, 120},
                    {0, -89, 120},
                    {0, -150, 89},
                    {0, -150, 214},
                    {Double.NaN, -150, 120}
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> new RobotPose.Position(p[0], p[1], p[2]));
        }
    }
}
