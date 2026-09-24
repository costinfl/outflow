package dev.costinfl.outflow.ingest.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.ingest.identity.IdentityKeys.IdentifiedRow;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdentityKeysTest {

    static ParsedRow row(int rowNo, String date, long amount, String description) {
        return row(rowNo, date, Optional.empty(), amount, description, Optional.empty());
    }

    static ParsedRow row(int rowNo, String date, Optional<String> valueDate, long amount, String description,
            Optional<String> reference) {
        return new ParsedRow(rowNo, Map.of(), LocalDate.parse(date), valueDate.map(LocalDate::parse), amount, "RON",
                description, reference);
    }

    static List<String> keys(long accountId, List<ParsedRow> rows) {
        return IdentityKeys.assign(accountId, rows).stream().map(IdentifiedRow::identityKey).toList();
    }

    /**
     * Pinned key_v1 outputs, hashes computed outside Java. If this fails, the normalizer or key material changed:
     * that would re-key every stored transaction. Add key_v2 instead of changing key_v1.
     */
    @Test
    void keyV1IsFrozen() {
        assertThat(keys(1, List.of(row(1, "2026-01-12", -1850, "CUMPARARE POS STARBUCKS AFI COTROCENI card ****4412"))))
                .containsExactly("key_v1:2ed52d3efd1511fcd7f59844836372c6fe4e44e0352f9491feabe11e5bedc7c4#1");
        assertThat(keys(7, List.of(row(3, "2026-02-02", -35696,
                "Plată POS KAUFLAND BUCUREŞTI 263 card ****4412 autorizare 832052"))))
                .containsExactly("key_v1:a7281874196b97301a34d8ddd8c05ae44161ac2bc430e131324622eba7b5d21b#1");
    }

    @Test
    void identicalRowsOnOneDateGetOccurrenceIndexes() {
        var coffee = "CUMPARARE POS STARBUCKS card ****4412";
        var k = keys(1, List.of(row(1, "2026-01-12", -1850, coffee), row(2, "2026-01-12", -1850, coffee),
                row(3, "2026-01-13", -1850, coffee)));

        assertThat(k.get(0)).endsWith("#1");
        assertThat(k.get(1)).endsWith("#2").startsWith(k.get(0).substring(0, k.get(0).length() - 1));
        assertThat(k.get(2)).endsWith("#1").isNotEqualTo(k.get(0)); // another date is another hash
    }

    @Test
    void keysDoNotDependOnFileOrRowPosition() {
        var a = row(1, "2026-01-12", -1850, "COFFEE");
        var b = row(2, "2026-01-12", -500, "BUS");
        var aLater = row(40, "2026-01-12", -1850, "COFFEE");
        var bLater = row(39, "2026-01-12", -500, "BUS");

        assertThat(keys(1, List.of(a, b))).containsExactlyInAnyOrderElementsOf(keys(1, List.of(bLater, aLater)));
    }

    @Test
    void occurrenceOrderIsValueDateThenRawDescriptionThenRowNo() {
        // Same hash (volatile auth codes normalize away), different value dates: order follows value date, not file.
        var late = row(1, "2026-01-12", Optional.of("2026-01-14"), -1850, "COFFEE autorizare 222", Optional.empty());
        var early = row(2, "2026-01-12", Optional.of("2026-01-13"), -1850, "COFFEE autorizare 111", Optional.empty());

        var k = keys(1, List.of(late, early));

        assertThat(k.get(1)).endsWith("#1");
        assertThat(k.get(0)).endsWith("#2");
    }

    @Test
    void accountIsPartOfTheKey() {
        var r = row(1, "2026-01-12", -1850, "COFFEE");

        assertThat(keys(1, List.of(r))).doesNotContainAnyElementsOf(keys(2, List.of(r)));
    }

    @Test
    void bankReferenceWinsOverContent() {
        var r = row(1, "2026-01-12", Optional.empty(), -1850, "COFFEE", Optional.of(" FT2601120001 "));

        assertThat(keys(1, List.of(r))).containsExactly("ref:FT2601120001");
    }

    @Test
    void repeatedReferenceIsOneRecordUnlessContentDiffers() {
        var r1 = row(1, "2026-01-12", Optional.empty(), -1850, "COFFEE", Optional.of("R1"));
        var r1again = row(2, "2026-01-12", Optional.empty(), -1850, "COFFEE", Optional.of("R1"));
        var r1clash = row(3, "2026-01-12", Optional.empty(), -9999, "OTHER", Optional.of("R1"));

        assertThat(keys(1, List.of(r1, r1again))).containsExactly("ref:R1", "ref:R1");
        assertThatThrownBy(() -> keys(1, List.of(r1, r1clash)))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("Row 3: bank reference repeats row 1 with different content");
    }
}
