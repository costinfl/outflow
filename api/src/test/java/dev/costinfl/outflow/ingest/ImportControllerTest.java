package dev.costinfl.outflow.ingest;

import static dev.costinfl.outflow.ingest.ImportFixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.FileOutcome.Status;
import dev.costinfl.outflow.ingest.account.Account;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** CP1.4: the upload endpoint and import summary, over real HTTP and Postgres. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outflow.parsers.locations=classpath:parsers/*.yml,classpath:test-parsers/*.yml")
@Import(TestcontainersConfiguration.class)
class ImportControllerTest {

    static final String JAN_MAR = "generic-2026-01-to-03.csv";
    static final String FEB_APR = "generic-2026-02-to-04.csv";
    static final String RO = "ro-style-2026-02.csv";
    static final String PLAIN_IBAN = "RO49AAAA1B31007593840000";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        ImportFixtures.reset(jdbc);
    }

    record Part(String name, byte[] content) {}

    static Part file(String name) throws Exception {
        return new Part(name, sample(name));
    }

    ResponseEntity<ImportSummary> upload(String query, Part... parts) {
        var body = new LinkedMultiValueMap<String, Object>();
        for (Part p : parts) {
            body.add("files", new ByteArrayResource(p.content()) {
                @Override
                public String getFilename() {
                    return p.name();
                }
            });
        }
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/imports" + query, new HttpEntity<>(body, headers), ImportSummary.class);
    }

    ImportSummary ok(String query, Part... parts) {
        var r = upload(query, parts);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    long createAccount(String name) {
        var r = http.postForEntity("/api/accounts", Map.of("name", name, "currency", "RON", "kind", "CURRENT"),
                Account.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().id();
    }

    @Test
    void firstRunDetectsTheAccountFromItsIbanAndAsksForTheOther() throws Exception {
        var s = ok("", file(RO), file(JAN_MAR));

        assertThat(s.files()).extracting(FileOutcome::status).containsExactly(Status.IMPORTED, Status.NEEDS_ACCOUNT);
        assertThat(s.accounts()).singleElement().satisfies(a -> {
            assertThat(a.created()).isTrue();
            assertThat(a.account().ibanMasked()).isEqualTo("RO49 •••• 0000");
            assertThat(a.account().name()).isEqualTo("Account ••0000");
            assertThat(a.account().currency()).isEqualTo("RON");
            assertThat(a.newTransactions()).isEqualTo(21);
            assertThat(a.periodFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        });
        assertThat(s.newTransactions()).isEqualTo(21);

        long main = createAccount("Main");
        var second = ok("?accountId=" + main, file(JAN_MAR));
        assertThat(second.files()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(Status.IMPORTED);
            assertThat(f.accountId()).isEqualTo(main);
            assertThat(f.newTransactions()).isEqualTo(63);
        });
    }

    @Test
    void sameIbanLaterGoesToTheSameAccount() throws Exception {
        long first = ok("", file(RO)).accounts().getFirst().account().id();
        byte[] trimmed = new String(sample(RO), "windows-1250").replaceAll("(?m)^26\\.02\\.2026.*\\r?\\n", "")
                .getBytes("windows-1250");

        var s = ok("", new Part("ro-without-last-day.csv", trimmed));

        assertThat(s.accounts()).singleElement().satisfies(a -> {
            assertThat(a.account().id()).isEqualTo(first);
            assertThat(a.created()).isFalse();
            assertThat(a.newTransactions()).isZero();
            assertThat(a.alreadyImported()).isEqualTo(20);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isEqualTo(1);
    }

    @Test
    void reUploadIsADuplicateAndOverlapGivesTheUnion() throws Exception {
        long main = createAccount("Main");
        ok("?accountId=" + main, file(JAN_MAR));

        var again = ok("?accountId=" + main, file(JAN_MAR));
        assertThat(again.files()).singleElement().extracting(FileOutcome::status).isEqualTo(Status.DUPLICATE_FILE);
        assertThat(again.newTransactions()).isZero();

        var overlap = ok("?accountId=" + main, file(FEB_APR));
        assertThat(overlap.newTransactions()).isEqualTo(21);
        assertThat(overlap.alreadyImported()).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction", Long.class)).isEqualTo(84);
    }

    @Test
    void severalFilesForOneAccountAreSummedPerAccount() throws Exception {
        long main = createAccount("Main");

        var s = ok("?accountId=" + main, file(JAN_MAR), file(FEB_APR));

        assertThat(s.files()).extracting(FileOutcome::newTransactions).containsExactly(63, 21);
        assertThat(s.accounts()).singleElement().satisfies(a -> {
            assertThat(a.created()).isFalse();
            assertThat(a.newTransactions()).isEqualTo(84);
            assertThat(a.alreadyImported()).isEqualTo(42);
            assertThat(a.periodFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(a.periodTo()).isEqualTo(LocalDate.of(2026, 4, 26));
        });
    }

    @Test
    void oneBrokenFileDoesNotAffectTheOthers() throws Exception {
        long main = createAccount("Main");
        var broken = new Part("broken.csv", "Date,Description,Amount,Currency\r\n2026-05-01,A,-1.00,RON\r\n2026-05-02,B,oops,RON\r\n"
                .getBytes(StandardCharsets.UTF_8));

        var s = ok("?accountId=" + main, file(JAN_MAR), broken, file(FEB_APR));

        assertThat(s.files()).extracting(FileOutcome::status).containsExactly(Status.IMPORTED, Status.FAILED, Status.IMPORTED);
        assertThat(s.files().get(1).message()).isEqualTo("Row 2: unreadable amount 'oops'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement_file", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE booking_date >= '2026-05-01'", Long.class)).isZero();
    }

    @Test
    void unknownFormatAsksForAParserAndOverrideIsHonoured() throws Exception {
        long main = createAccount("Main");
        var odd = new Part("odd.csv", "When;What;HowMuch\r\n01.05.2026;x;1\r\n".getBytes(StandardCharsets.UTF_8));

        var s = ok("?accountId=" + main, odd);
        assertThat(s.files()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(Status.NEEDS_PARSER);
            assertThat(f.candidates()).extracting(FileOutcome.ParserCandidate::parserId)
                    .containsExactlyInAnyOrder("generic-csv-v1", "ing-ro-csv-v1", "ro-style-csv-v1");
            assertThat(f.candidates()).allSatisfy(c -> assertThat(c.reason()).isNotBlank());
        });

        var forced = ok("?accountId=" + main + "&parserId=generic-csv-v1", odd);
        assertThat(forced.files().getFirst().status()).isEqualTo(Status.FAILED);
        assertThat(forced.files().getFirst().message()).startsWith("Header not found");

        var unknown = ok("?accountId=" + main + "&parserId=nope", odd);
        assertThat(unknown.files().getFirst().message()).isEqualTo("Unknown parser 'nope'");
    }

    @Test
    void aFileForAnotherAccountIsRefused() throws Exception {
        long main = ok("", file(RO)).accounts().getFirst().account().id();
        long other = createAccount("Other");
        jdbc.update("UPDATE account SET iban_hash = decode(repeat('ab', 32), 'hex'), iban_masked = 'GB82 •••• 5432' WHERE id = ?", other);

        var s = ok("?accountId=" + other, file(RO));

        assertThat(s.files()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(Status.FAILED);
            assertThat(f.message()).isEqualTo("This file belongs to account RO49 •••• 0000, not the one you picked");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE account_id = ?", Long.class, other)).isZero();
        assertThat(main).isNotEqualTo(other);
    }

    @Test
    void thePlainIbanIsStoredNowhereAndNeverReturned() throws Exception {
        var r = upload("", file(RO));

        assertThat(r.getBody().accounts()).isNotEmpty();
        var rawResponse = http.getForObject("/api/accounts", String.class);
        assertThat(rawResponse).contains("RO49 •••• 0000").doesNotContain(PLAIN_IBAN);
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM account a", String.class))
                .singleElement().asString().doesNotContain(PLAIN_IBAN);
        assertThat(jdbc.queryForObject("SELECT octet_length(iban_hash) FROM account", Integer.class)).isEqualTo(32);
    }

    @Test
    void requestValidation() throws Exception {
        assertThat(upload("").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var tooMany = new Part[21];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = new Part("f" + i + ".csv", new byte[] {'x'});
        }
        assertThat(upload("", tooMany).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload("?accountId=999", file(JAN_MAR)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var badAccount = http.postForEntity("/api/accounts", Map.of("name", "X", "currency", "ron", "kind", "CURRENT"), String.class);
        assertThat(badAccount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForObject("/api/accounts", Account[].class)).isEmpty();
    }

    @Test
    void accountsAreListed() {
        createAccount("Main");
        createAccount("Savings");

        assertThat(List.of(http.getForObject("/api/accounts", Account[].class)))
                .extracting(Account::name).containsExactly("Main", "Savings");
    }
}
