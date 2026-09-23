package dev.costinfl.outflow.system;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness plus a database round-trip, so the SPA placeholder can prove the whole stack is wired.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    public enum Status { UP, DOWN }

    public record HealthResponse(Status status, Status database, String schemaVersion) {}

    private final JdbcTemplate jdbc;
    private final Flyway flyway;

    public HealthController(JdbcTemplate jdbc, Flyway flyway) {
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(new HealthResponse(Status.DOWN, Status.DOWN, null));
        }
        MigrationInfo current = flyway.info().current();
        String schemaVersion = current == null ? null : current.getVersion().getVersion();
        return ResponseEntity.ok(new HealthResponse(Status.UP, Status.UP, schemaVersion));
    }
}
