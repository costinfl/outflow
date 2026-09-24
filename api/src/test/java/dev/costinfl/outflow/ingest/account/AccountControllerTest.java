package dev.costinfl.outflow.ingest.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP3.4: renaming a detected account ("Account ••0000" → "Main"). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AccountControllerTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long id;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        id = ImportFixtures.newAccount(jdbc, "Account ••0000");
    }

    org.springframework.http.ResponseEntity<String> patch(long accountId, Map<String, Object> body) {
        return http.exchange("/api/accounts/" + accountId, HttpMethod.PATCH, new HttpEntity<>(body), String.class);
    }

    @Test
    void renameAndChangeKind() {
        assertThat(patch(id, Map.of("name", "  Main  ")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch(id, Map.of("kind", "SAVINGS")).getStatusCode()).isEqualTo(HttpStatus.OK);

        var account = http.getForObject("/api/accounts", Account[].class)[0];
        assertThat(account.name()).isEqualTo("Main");
        assertThat(account.kind()).isEqualTo(Account.Kind.SAVINGS);
    }

    @Test
    void validation() {
        assertThat(patch(id, Map.of("name", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(999_999, Map.of("name", "X")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
