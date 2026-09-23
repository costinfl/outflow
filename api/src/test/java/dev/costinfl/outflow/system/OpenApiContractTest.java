package dev.costinfl.outflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.costinfl.outflow.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

/**
 * The committed {@code api/openapi.json} is the contract the web client is generated from.
 * Fails when the live spec drifts; refresh with {@code ./mvnw -pl api verify -Dopenapi.update=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    private static final Path SPEC = Path.of("openapi.json");

    @Autowired
    TestRestTemplate http;

    @Test
    void committedSpecMatchesLiveApi() throws Exception {
        var mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var live = mapper.writeValueAsString(
                mapper.readValue(http.getForObject("/api/openapi.json", String.class), Object.class)) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(SPEC, live);
        }

        assertThat(SPEC).as("api/openapi.json missing; run with -Dopenapi.update=true").exists();
        assertThat(Files.readString(SPEC))
                .as("api/openapi.json is stale; run ./mvnw -pl api verify -Dopenapi.update=true, then npm --prefix web run gen:api")
                .isEqualTo(live);
    }
}
