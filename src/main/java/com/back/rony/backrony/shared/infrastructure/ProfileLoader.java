package com.back.rony.backrony.shared.infrastructure;

import com.back.rony.backrony.robotics.domain.RobotPose.Position;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ProfileLoader {
    private ProfileLoader() {}

    public static RobotProfile load(Path path, ObjectMapper mapper) {
        try {
            byte[] bytes =
                    Files.readString(path, StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .getBytes(StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(bytes);
            if (root.path("schemaVersion").asInt() != 1) {
                throw new IllegalArgumentException("Versión de perfil no admitida.");
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : root.path("values").properties()) {
                if (property.getValue().isNumber())
                    values.put(property.getKey(), property.getValue().asDouble());
            }
            Map<String, RobotProfile.Destination> bins = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : root.path("bins").properties()) {
                JsonNode p = property.getValue().path("positionMm");
                var transit = new ArrayList<Position>();
                property.getValue()
                        .path("transitMm")
                        .forEach(
                                point ->
                                        transit.add(
                                                new Position(
                                                        point.get(0).asDouble(),
                                                        point.get(1).asDouble(),
                                                        point.get(2).asDouble())));
                bins.put(
                        property.getKey(),
                        new RobotProfile.Destination(
                                property.getValue().path("label").asString(),
                                new Position(
                                        p.get(0).asDouble(),
                                        p.get(1).asDouble(),
                                        p.get(2).asDouble()),
                                property.getValue().path("baseAngleDeg").asDouble(),
                                transit));
            }
            var categories = new ArrayList<String>();
            root.path("enabledCategories").forEach(c -> categories.add(c.asString()));
            RobotProfile profile =
                    new RobotProfile(
                            values,
                            bins,
                            categories,
                            new RobotProfile.Workspace(
                                    root.path("workspaceMm").path("x").get(0).asDouble(),
                                    root.path("workspaceMm").path("x").get(1).asDouble(),
                                    root.path("workspaceMm").path("y").get(0).asDouble(),
                                    root.path("workspaceMm").path("y").get(1).asDouble(),
                                    root.path("workspaceMm").path("z").get(0).asDouble(),
                                    root.path("workspaceMm").path("z").get(1).asDouble()),
                            root.path("provisionalDestinations").asBoolean(),
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            profile.home();
            if (profile.integer("STABLE_FRAMES") < 1
                    || profile.value("AUTO_MIN_CONF") < 0.8
                    || profile.integer("GRIPPER_PICK") != 80) {
                throw new IllegalArgumentException(
                        "Revisa los valores exportados del notebook actual.");
            }
            return profile;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "No se pudo leer el perfil del notebook: " + path.toAbsolutePath());
        }
    }
}
