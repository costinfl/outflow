package dev.costinfl.outflow.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.FileOutcome.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.18: a CAMT.053 statement through the whole upload: detected without choosing a parser, its account found (or
 * created) from the statement's IBAN, and imported exactly once, even when the same statement comes again in another
 * file (bank references are the identity).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Camt053ImportTest {

    static final Path GOLDEN = Path.of("..", "samples", "synthetic", "camt053-2026-03.xml");

    @Autowired UploadService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
    }

    @Test
    void theStatementNamesItsAccountAndImportsOnce() throws Exception {
        byte[] content = Files.readAllBytes(GOLDEN);

        var first = uploads.importOne("march.xml", content, Optional.empty(), Optional.empty());

        assertThat(first.outcome().status()).isEqualTo(Status.IMPORTED);
        assertThat(first.outcome().parserId()).isEqualTo("camt053-v1");
        assertThat(first.accountCreated()).isTrue();
        assertThat(first.outcome().newTransactions()).isEqualTo(8);
        assertThat(jdbc.queryForMap("SELECT iban_masked, currency FROM account"))
                .containsEntry("currency", "RON").containsEntry("iban_masked", first.account().orElseThrow().ibanMasked());
        assertThat(jdbc.queryForObject("SELECT sum(amount_minor) FROM transaction", Long.class)).isEqualTo(494_791);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE status = 'PENDING'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE identity_key LIKE 'ref:SYN-%'", Long.class))
                .isEqualTo(7);

        // The same file: recognised as such. The same statement re-exported (another file): nothing new.
        var again = uploads.importOne("march.xml", content, Optional.empty(), Optional.empty());
        assertThat(again.outcome().status()).isEqualTo(Status.DUPLICATE_FILE);
        byte[] reexported = new String(content, StandardCharsets.UTF_8)
                .replace("<MsgId>SYNTH-2026-03</MsgId>", "<MsgId>SYNTH-2026-03-B</MsgId>").getBytes(StandardCharsets.UTF_8);
        var other = uploads.importOne("march-again.xml", reexported, Optional.empty(), Optional.empty());
        assertThat(other.outcome().status()).isEqualTo(Status.IMPORTED);
        assertThat(other.accountCreated()).isFalse(); // the same account, found by its IBAN
        assertThat(other.outcome().newTransactions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isEqualTo(1);
    }
}
