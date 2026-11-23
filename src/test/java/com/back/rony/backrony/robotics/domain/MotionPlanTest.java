package com.back.rony.backrony.robotics.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
import com.back.rony.backrony.monitoring.domain.Detection;
import org.junit.jupiter.api.Test;

class MotionPlanTest {
    @Test
    void allThreeMaterialsUseTheirDestinationAndReturnOpen() {
        var profile = TestProfile.profile();
        for (String category : profile.enabledCategories()) {
            var steps = MotionPlan.sort(TestProfile.target(category), profile);
            assertTrue(steps.size() <= 16);
            assertEquals("plastic".equals(category) ? 16 : 14, steps.size());
            assertEquals(80, steps.get(3).gripper());
            assertEquals(2, steps.get(3).duration());
            int opening = -1;
            for (int i = 0; i < steps.size(); i++) {
                if (Integer.valueOf(0).equals(steps.get(i).gripper())) opening = i;
            }
            assertTrue(opening > 7);
            assertEquals(
                    profile.destination(category).position(), steps.get(opening - 1).position());
            assertEquals(profile.home(), steps.getLast().position());
        }
        assertEquals(
                3,
                profile.bins().values().stream()
                        .map(RobotProfile.Destination::position)
                        .distinct()
                        .count());
        assertEquals(8, profile.integer("STABLE_FRAMES"));
    }

    @Test
    void transitKeepsTheHomeRadiusAndStaysInsideTheBaseMargin() {
        var p = TestProfile.profile();
        double radius = Math.hypot(p.home().x(), p.home().y());
        for (var destination : p.bins().values()) {
            double previous = 120;
            for (var point : destination.transitMm()) {
                double base = 120 + Math.toDegrees(Math.atan2(point.x(), -point.y()));
                assertTrue(base >= 10 && base <= 230.001);
                assertTrue(base - previous <= 30.001);
                assertEquals(radius, Math.hypot(point.x(), point.y()), .001);
                assertEquals(p.home().z(), point.z());
                previous = base;
            }
            assertEquals(destination.baseAngleDeg(), previous, .001);
            assertTrue(
                    !p.pickupWorkspace()
                            .contains(destination.position().x(), destination.position().y(), 90));
        }
        assertEquals(230, p.destination("plastic").baseAngleDeg());
    }

    @Test
    void resetFromFarDropReturnsThroughIntermediatePoints() {
        var p = TestProfile.profile();
        var bin = p.destination("plastic").position();
        var steps = MotionPlan.reset(new RobotPose(bin.x(), bin.y(), bin.z(), 0), p);
        assertEquals(5, steps.size());
        assertEquals(p.destination("cardboard").position(), steps.getFirst().position());
        assertEquals(p.home(), steps.get(3).position());
    }

    @Test
    void resetLiftsBeforeHomeThenOpens() {
        var p = TestProfile.profile();
        var steps = MotionPlan.reset(new RobotPose(10, -220, 90, 80), p);
        assertEquals(p.home().z(), steps.getFirst().position().z());
        assertEquals(10, steps.getFirst().position().x());
        assertEquals(-220, steps.getFirst().position().y());
        assertEquals(p.home(), steps.get(1).position());
        assertEquals(0, steps.getLast().gripper());
    }

    @Test
    void everyLateralMoveIsAtHomeHeightAndDisposalStartsAfterHome() {
        var p = TestProfile.profile();
        for (String category : p.enabledCategories()) {
            var steps = MotionPlan.sort(TestProfile.target(category), p);
            var previous = p.home();
            boolean closed = false, homeAfterGrasp = false;
            for (var step : steps) {
                if (step.gripper() != null) {
                    closed = step.gripper() == 80;
                    continue;
                }
                var next = step.position();
                boolean lateral = Math.hypot(next.x() - previous.x(), next.y() - previous.y()) > 2;
                if (lateral) {
                    assertEquals(p.home().z(), previous.z(), .001);
                    assertEquals(p.home().z(), next.z(), .001);
                }
                if (closed && next.equals(p.home())) homeAfterGrasp = true;
                if (closed && p.destination(category).transitMm().contains(next)) {
                    assertTrue(homeAfterGrasp, "No disposal turn before HOME with object");
                }
                previous = next;
            }
        }
    }

    @Test
    void resetAtIntermediateLowHeightRaisesVerticallyBeforeHome() {
        var p = TestProfile.profile();
        var steps = MotionPlan.reset(new RobotPose(20, -200, 150, 80), p);
        assertEquals(20, steps.getFirst().position().x());
        assertEquals(-200, steps.getFirst().position().y());
        assertEquals(p.home().z(), steps.getFirst().position().z());
        assertEquals(p.home(), steps.get(1).position());
    }

    @Test
    void targetsInsideAnyDropZoneAreRejected() {
        var p = TestProfile.profile();
        var d = TestProfile.target("plastic");
        var b = p.destination("plastic").position();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.sort(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .91,
                                        d.pixel(),
                                        null,
                                        new Detection.Point(b.x(), b.y()),
                                        d.destination()),
                                p));
    }

    @Test
    void broaderTransportEnvelopeDoesNotExpandPickupArea() {
        var p = TestProfile.profile();
        var d = TestProfile.target("plastic");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.sort(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .95,
                                        d.pixel(),
                                        null,
                                        new Detection.Point(101, -150),
                                        d.destination()),
                                p));
    }

    @Test
    void lowConfidenceAndMissingProjectionAreRejected() {
        var p = TestProfile.profile();
        var d = TestProfile.target("plastic");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.sort(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .79,
                                        d.pixel(),
                                        d.offsetMm(),
                                        d.targetMm(),
                                        d.destination()),
                                p));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MotionPlan.sort(
                                new Detection(
                                        d.residue(),
                                        d.shortName(),
                                        d.category(),
                                        .91,
                                        d.pixel(),
                                        null,
                                        null,
                                        d.destination()),
                                p));
    }

    @Test
    void invalidWorkspaceAndLegacyGripperCommandsAreRejected() {
        for (double[] p :
                new double[][] {
                    {251, 0, 120},
                    {0, -251, 120},
                    {0, 0, 120},
                    {0, -150, 89},
                    {0, -150, 214},
                    {Double.NaN, -150, 120}
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> new RobotPose.Position(p[0], p[1], p[2]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new MotionPlan.Step(3, "legacy", null, 1, 82));
    }
}
