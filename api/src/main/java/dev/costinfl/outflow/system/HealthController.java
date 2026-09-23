package dev.costinfl.outflow.system;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness plus a database round-trip, so the SPA placeholder can prove the whole stack is wired.
 */
@RestController
@RequestMapping(path = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "system")
public class HealthController {

    public enum Status { UP, DOWN }

    public record HealthResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status database,
            @Schema(description = "Applied Flyway version; absent when the database is down") String schemaVersion) {}

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
