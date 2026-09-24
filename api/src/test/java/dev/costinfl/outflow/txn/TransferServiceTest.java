package dev.costinfl.outflow.txn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.ingest.account.IbanHasher;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.recurring.RecurrenceService;
import dev.costinfl.outflow.recurring.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP5.1: own-account transfers are paired, excluded from spending and from recurrence. Synthetic IBANs (valid checksums,
 * fake bank code TEST). March 2026: Mon 2, Fri 6, Mon 9, Wed 11, Thu 12.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceTest {

    static final String MAIN_IBAN = "RO08TEST0000000000000001";
    static final String SAVINGS_IBAN = "RO78TEST0000000000000002";
    static final String CARD_IBAN = "RO51TEST0000000000000003";

    @Autowired UploadService uploads;
    @Autowired TransferService transfers;
    @Autowired CategoryService categories;
    @Autowired InsightService insights;
    @Autowired RecurrenceService recurrence;
    @Autowired SubscriptionService subscriptions;
    @Autowired IbanHasher hasher;
    @Autowired JdbcTemplate jdbc;

    long main, savings, card;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        main = account("Main", MAIN_IBAN, "CURRENT");
        savings = account("Savings", SAVINGS_IBAN, "SAVINGS");
        card = account("Card", CARD_IBAN, "CARD");
    }

    long account(String name, String iban, String kind) {
        var parsed = Iban.parse(iban).orElseThrow();
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, iban_hash, iban_masked, currency, kind)
                VALUES (1, 1, ?, ?, ?, 'RON', ?) RETURNING id""", Long.class, name, hasher.hash(parsed), parsed.masked(), kind);
    }

    /** Rows as "date,description,amount". Returns the upload's transfer count. */
    int load(long account, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(",RON\r\n");
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return uploads.importOne("f" + account + "-" + csv.hashCode() + ".csv", bytes, Optional.of(account), Optional.empty())
                .outcome().transfers();
    }

    Map<String, Object> tx(long account, String date) {
        return jdbc.queryForMap("""
                SELECT t.id, t.transfer_state, t.transfer_account_id, t.transfer_pair_id, c.code, t.category_source
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.account_id = ? AND t.booking_date = ?::date""", account, date);
    }

    @Nested
    class Pairing {

        @Test
        void oppositeEqualAmountsWithinThreeBusinessDaysArePaired() throws Exception {
            load(main, "2026-03-06,ORDIN PLATA STASH,-1000.00");
            int transfersFound = load(savings, "2026-03-11,INCASARE ORDIN PLATA,1000.00"); // Fri → Wed: 3 business days

            assertThat(transfersFound).isEqualTo(2);
            var out = tx(main, "2026-03-06");
            var in = tx(savings, "2026-03-11");
            assertThat(out.get("transfer_state")).isEqualTo("PAIRED");
            assertThat(out.get("transfer_account_id")).isEqualTo(savings);
            assertThat(in.get("transfer_account_id")).isEqualTo(main);
            assertThat(out.get("transfer_pair_id")).isEqualTo(in.get("transfer_pair_id"));
            assertThat(out.get("code")).isEqualTo("TRANSFER");
            assertThat(out.get("category_source")).isEqualTo("SYSTEM");
            assertThat(in.get("code")).isEqualTo("TRANSFER");
            assertThat(jdbc.queryForMap("SELECT method, business_days FROM transfer_pair"))
                    .containsEntry("method", "AMOUNT_DATE").containsEntry("business_days", 3);
            assertThat(insights.month(YearMonth.of(2026, 3), "RON").spentMinor()).isZero();
        }

        @Test
        void fourBusinessDaysApartIsNotATransfer() throws Exception {
            load(main, "2026-03-06,ORDIN PLATA STASH,-1000.00");
            load(savings, "2026-03-12,INCASARE ORDIN PLATA,1000.00");

            assertThat(tx(main, "2026-03-06").get("transfer_state")).isNull();
            assertThat(insights.month(YearMonth.of(2026, 3), "RON").spentMinor()).isEqualTo(100_000);
        }

        @Test
        void sameAccountAndDifferentAmountsNeverPair() throws Exception {
            load(main, "2026-03-02,ORDIN PLATA STASH,-200.00", "2026-03-03,RETUR ORDIN PLATA,200.00");
            load(savings, "2026-03-03,INCASARE ORDIN PLATA,199.99");

            assertThat(transfers.counts()).isEmpty();
        }

        @Test
        void greedyBySmallestGap() throws Exception {
            load(main, "2026-03-02,ORDIN PLATA STASH,-500.00");
            load(savings, "2026-03-03,INCASARE ORDIN PLATA,500.00", "2026-03-05,INCASARE ORDIN PLATA,500.00");

            assertThat(tx(savings, "2026-03-03").get("transfer_state")).isEqualTo("PAIRED");
            assertThat(tx(savings, "2026-03-05").get("transfer_state")).isNull();
        }

        @Test
        void aTieStaysUnpairedUnlessAnIbanDecides() throws Exception {
            load(main, "2026-03-02,ORDIN PLATA STASH,-300.00");
            load(savings, "2026-03-03,INCASARE ORDIN PLATA,300.00");
            load(card, "2026-03-03,ALIMENTARE CARD,300.00");

            // The savings deposit was paired on its own upload; the card deposit turns it into a tie, so it dissolves
            // and the money out is ordinary (uncategorized) spending again.
            assertThat(transfers.counts()).isEmpty();
            assertThat(tx(main, "2026-03-02")).containsEntry("code", null).containsEntry("transfer_pair_id", null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_pair", Long.class)).isZero();

            // The same money out, but its description names the card account: only that pair is possible.
            load(main, "2026-03-09,TRANSFER CATRE " + CARD_IBAN + ",-300.00");
            load(savings, "2026-03-10,INCASARE ORDIN PLATA,300.00");
            load(card, "2026-03-10,ALIMENTARE CARD,300.00");

            assertThat(tx(card, "2026-03-10").get("transfer_state")).isEqualTo("PAIRED");
            assertThat(tx(savings, "2026-03-10").get("transfer_state")).isNull();
            assertThat(jdbc.queryForObject("SELECT method FROM transfer_pair", String.class)).isEqualTo("IBAN");
        }

        @Test
        void pairingAgainFindsNothingNew() throws Exception {
            load(main, "2026-03-06,ORDIN PLATA STASH,-1000.00");
            load(savings, "2026-03-09,INCASARE ORDIN PLATA,1000.00");

            assertThat(transfers.pairAll()).isEqualTo(new TransferService.Result(0, 0));
        }
    }

    @Nested
    class OneSided {

        @Test
        void aCounterpartyIbanMarksItProvisionalUntilTheOtherSideArrives() throws Exception {
            int first = load(main, "2026-03-06,TRANSFER CATRE " + SAVINGS_IBAN + ",-250.00");

            assertThat(first).isEqualTo(1);
            var out = tx(main, "2026-03-06");
            assertThat(out.get("transfer_state")).isEqualTo("PROVISIONAL");
            assertThat(out.get("transfer_account_id")).isEqualTo(savings);
            assertThat(out.get("code")).isEqualTo("TRANSFER");
            assertThat(insights.month(YearMonth.of(2026, 3), "RON").spentMinor()).isZero();

            load(savings, "2026-03-09,INCASARE,250.00");

            assertThat(tx(main, "2026-03-06").get("transfer_state")).isEqualTo("PAIRED");
            assertThat(tx(savings, "2026-03-09").get("transfer_state")).isEqualTo("PAIRED");
            assertThat(jdbc.queryForObject("SELECT method FROM transfer_pair", String.class)).isEqualTo("IBAN");
        }

        @Test
        void anIbanOfSomeoneElseIsNotATransfer() throws Exception {
            load(main, "2026-03-06,TRANSFER CATRE RO49AAAA1B31007593840000,-250.00");

            assertThat(tx(main, "2026-03-06").get("transfer_state")).isNull();
        }
    }

    @Nested
    class Decisions {

        @Test
        void aUserCategoryIsNeverOverwritten() throws Exception {
            load(main, "2026-03-06,ORDIN PLATA STASH,-400.00");
            long out = (Long) tx(main, "2026-03-06").get("id");
            categories.setCategory(out, categories.categoryId("GROCERIES"), false);

            load(savings, "2026-03-09,INCASARE ORDIN PLATA,400.00");

            assertThat(tx(main, "2026-03-06")).containsEntry("code", "GROCERIES").containsEntry("transfer_state", null);
            assertThat(tx(savings, "2026-03-09").get("transfer_state")).isNull();
        }

        @Test
        void recategorizingKeepsPairedTransfers() throws Exception {
            load(main, "2026-03-06,ORDIN PLATA STASH,-1000.00");
            load(savings, "2026-03-09,INCASARE ORDIN PLATA,1000.00");

            categories.categorizeAll();

            assertThat(tx(main, "2026-03-06")).containsEntry("code", "TRANSFER").containsEntry("category_source", "SYSTEM");
        }
    }

    /** M5 acceptance: a monthly savings transfer is never counted as spending nor proposed as a subscription. */
    @Test
    void aMonthlySavingsTransferIsNeitherSpendingNorASubscription() throws Exception {
        String[] outs = new String[6];
        String[] ins = new String[6];
        for (int m = 1; m <= 6; m++) {
            String date = LocalDate.of(2026, m, 5).toString();
            outs[m - 1] = date + ",ORDIN PLATA STASH,-1000.00";
            ins[m - 1] = date + ",INCASARE ORDIN PLATA,1000.00";
        }
        load(main, outs);
        load(savings, ins);

        assertThat(transfers.counts()).containsEntry("PAIRED", 12L);
        for (int m = 1; m <= 6; m++) {
            assertThat(insights.month(YearMonth.of(2026, m), "RON").spentMinor()).isZero();
        }
        assertThat(recurrence.detect(LocalDate.of(2026, 6, 20))).isEmpty();
        assertThat(subscriptions.list()).isEmpty();
    }

    @Test
    void businessDays() {
        LocalDate fri = LocalDate.of(2026, 3, 6);
        assertThat(TransferService.businessDays(fri, fri)).isZero();
        assertThat(TransferService.businessDays(fri, fri.plusDays(3))).isEqualTo(1); // Monday
        assertThat(TransferService.businessDays(fri.plusDays(5), fri)).isEqualTo(3); // Wednesday, either order
        assertThat(TransferService.businessDays(fri.plusDays(1), fri.plusDays(3))).isEqualTo(1); // Saturday → Monday
        assertThat(TransferService.businessDays(fri, fri.plusDays(60))).isGreaterThan(3);
    }
}
