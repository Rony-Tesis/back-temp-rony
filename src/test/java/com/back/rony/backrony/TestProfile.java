package com.back.rony.backrony;

import com.back.rony.backrony.monitoring.domain.Detection;
import com.back.rony.backrony.robotics.domain.RobotProfile;
import com.back.rony.backrony.shared.infrastructure.ProfileLoader;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

public final class TestProfile {
    private TestProfile() {}

    public static RobotProfile profile() {
        return ProfileLoader.load(
                Path.of("../rony-yolo-isaac/runtime/rony_profile.json"),
                JsonMapper.builder().build());
    }

    public static Detection target(String category) {
        return new Detection(
                category,
                category,
                category,
                .91,
                new Detection.Point(320, 240),
                new Detection.Point(10, -57.06),
                new Detection.Point(10, -220),
                "Destino provisional");
    }
}
