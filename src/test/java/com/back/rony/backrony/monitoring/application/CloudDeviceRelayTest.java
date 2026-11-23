package com.back.rony.backrony.monitoring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.back.rony.backrony.TestProfile;
import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.monitoring.domain.ProcessState;
import com.back.rony.backrony.robotics.application.PhysicalMotionService.ReportedPosition;
import com.back.rony.backrony.robotics.application.PhysicalMotionService.RobotState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class CloudDeviceRelayTest {
    static final class History implements HistoryPort {
        final List<Operation> rows = new ArrayList<>();

        public void saveOperation(Operation operation, String mode) {
            rows.add(operation);
        }

        public List<Operation> recentOperations() {
            return List.copyOf(rows);
        }

        public Summary summary(Instant since) {
            return new Summary(rows.size(), rows.size(), 0, 0, 0, rows.size());
        }

        public long countGrasps() {
            return 0;
        }

        public void saveGrasp(Detection detection) {
            throw new AssertionError("Cloud must not create grasps");
        }
    }

    public static CloudDeviceRelay.Exchange packet(
            String boot, long sequence, List<Operation> operations, List<String> ack) {
        return packet(boot, sequence, operations, ack, "idle", false, false, 0, -162.94, 212.8);
    }

    private static CloudDeviceRelay.Exchange packet(
            String boot,
            long sequence,
            List<Operation> operations,
            List<String> ack,
            String state,
            boolean auto,
            boolean active,
            double x,
            double y,
            double z) {
        var robot =
                new RobotState(
                        true,
                        state,
                        "YOLO dentro del JetMax",
                        auto,
                        active,
                        null,
                        0,
                        new ReportedPosition(x, y, z),
                        0.,
                        null,
                        null,
                        null,
                        0,
                        true);
        var process =
                new ProcessState(0, 0, 0, List.of(0L, 0L, 0L, 0L, 0L, 0L), 0, false, null, false);
        return new CloudDeviceRelay.Exchange(
                "jetmax-01",
                boot,
                sequence,
                TestProfile.profile().sha256(),
                "vertical-home-before-disposal-v1",
                robot,
                null,
                null,
                process,
                true,
                0,
                8,
                5,
                null,
                operations,
                ack);
    }

    @Test
    void manualControlsRequireStoppedAutomaticAndStorageRequiresHome() {
        var relay = new CloudDeviceRelay(new History(), TestProfile.profile(), "jetmax-01");
        String boot = UUID.randomUUID().toString();
        relay.exchange(
                packet(boot, 1, List.of(), List.of(), "waiting", true, false, 0, -162.94, 212.8));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("close-gripper"));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("park"));
        relay.exchange(
                packet(boot, 2, List.of(), List.of(), "moving", false, true, 0, -162.94, 212.8));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("close-gripper"));
        relay.exchange(
                packet(boot, 3, List.of(), List.of(), "fault", false, false, 0, -162.94, 212.8));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("close-gripper"));
        relay.exchange(
                packet(boot, 4, List.of(), List.of(), "cancelled", false, false, 10, -210, 90));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("park"));
        relay.physicalCommand("close-gripper");
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("park"));
        var reply = relay.exchange(packet(boot, 5, List.of(), List.of()));
        assertEquals("close-gripper", reply.commands().getFirst().action());
        relay.exchange(packet(boot, 6, List.of(), List.of(reply.commands().getFirst().id())));
        relay.physicalCommand("park");
        reply = relay.exchange(packet(boot, 7, List.of(), List.of()));
        assertEquals("park", reply.commands().getFirst().action());
        relay.exchange(
                packet(
                        boot,
                        8,
                        List.of(),
                        List.of(reply.commands().getFirst().id()),
                        "parked",
                        false,
                        false,
                        -42.6878,
                        -27.0905,
                        225));
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("arm"));
        relay.physicalCommand("reset");
        assertEquals(
                "reset",
                relay.exchange(packet(boot, 9, List.of(), List.of()))
                        .commands()
                        .getFirst()
                        .action());
    }

    @Test
    void receivesTelemetryQueuesBootBoundCommandsAndAcknowledgesThem() {
        var relay = new CloudDeviceRelay(new History(), TestProfile.profile(), "jetmax-01");
        String boot = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> relay.physicalCommand("arm"));
        relay.exchange(packet(boot, 1, List.of(), List.of()));
        relay.physicalCommand("arm");
        var reply = relay.exchange(packet(boot, 2, List.of(), List.of()));
        assertEquals(1, reply.commands().size());
        assertEquals(boot, reply.commands().getFirst().bootId());
        assertEquals("arm", reply.commands().getFirst().action());
        assertTrue(
                relay.exchange(
                                packet(
                                        boot,
                                        3,
                                        List.of(),
                                        List.of(reply.commands().getFirst().id())))
                        .commands()
                        .isEmpty());
        assertEquals("jetmax", relay.status().get("executionLocation"));
        assertEquals("physical", relay.snapshot().motionMode());
    }

    @Test
    void cancelSupersedesQueuedArmAndRestartDoesNotReplayCommands() {
        var relay = new CloudDeviceRelay(new History(), TestProfile.profile(), "jetmax-01");
        String boot = UUID.randomUUID().toString();
        relay.exchange(packet(boot, 1, List.of(), List.of()));
        relay.physicalCommand("arm");
        relay.physicalCommand("cancel");
        var reply = relay.exchange(packet(boot, 2, List.of(), List.of()));
        assertEquals(
                List.of("cancel"),
                reply.commands().stream().map(CloudDeviceRelay.DeviceCommand::action).toList());
        assertTrue(
                relay.exchange(packet(UUID.randomUUID().toString(), 1, List.of(), List.of()))
                        .commands()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> relay.exchange(packet(boot, 3, List.of(), List.of())));
    }

    @Test
    void operationRetriesDoNotDuplicateHistoryOrInventDepositProof() {
        var history = new History();
        var relay = new CloudDeviceRelay(history, TestProfile.profile(), "jetmax-01");
        String boot = UUID.randomUUID().toString();
        var event =
                new Operation(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "Plástico",
                        "plastic",
                        .95,
                        "unverified",
                        30000,
                        "Plástico · destino provisional",
                        null);
        relay.exchange(packet(boot, 1, List.of(event), List.of()));
        relay.exchange(packet(boot, 2, List.of(event), List.of()));
        assertEquals(1, history.rows.size());
        var falseSuccess =
                new Operation(
                        event.id(),
                        Instant.now(),
                        "Plástico",
                        "plastic",
                        .95,
                        "success",
                        30000,
                        "Destino",
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () -> relay.exchange(packet(boot, 3, List.of(falseSuccess), List.of())));
    }
}
