package dev.costinfl.outflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.system.HealthController.HealthResponse;
import dev.costinfl.outflow.system.HealthController.Status;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthControllerTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    Flyway flyway;

    @Test
    void healthReportsUpWithDatabaseRoundTrip() {
        var response = http.getForEntity("/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthResponse(Status.UP, Status.UP, latestMigrationVersion()));
    }

    @Test
    void flywayAppliedEveryMigrationAgainstRealPostgres() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion()).startsWith("1");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
    }

    /** Highest V<n> in db/migration, read from the classpath rather than hard-coded. */
    private String latestMigrationVersion() {
        var all = flyway.info().all();
        return all[all.length - 1].getVersion().getVersion();
    }

    @Test
    void openApiDocumentExposesHealthEndpoint() {
        var spec = http.getForObject("/api/openapi.json", String.class);

        assertThat(spec).contains("\"/api/health\"");
    }
}
