package com.back.rony.backrony.shared.presentation;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deployment readiness depends on the database, not on a robot being online. */
@RestController
public final class HealthController {
    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try (var connection = dataSource.getConnection()) {
            if (connection.isValid(2)) return ResponseEntity.ok(Map.of("status", "up"));
        } catch (SQLException ignored) {
            // Never return database endpoints, SQL errors or credentials publicly.
        }
        return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
    }
}
