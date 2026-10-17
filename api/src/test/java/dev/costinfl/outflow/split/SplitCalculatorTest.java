package dev.costinfl.outflow.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.split.SplitCalculator.Allocation;
import dev.costinfl.outflow.split.SplitCalculator.Mode;
import dev.costinfl.outflow.split.SplitCalculator.PersonInput;
import dev.costinfl.outflow.split.SplitCalculator.PersonShare;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CP6.21: the four split modes, in exact minor units; the parts always add up to the expense. */
class SplitCalculatorTest {

    static PersonInput person(String name) {
        return new PersonInput(name, null, null, null);
    }

    static PersonInput percent(String name, String pct) {
        return new PersonInput(name, new BigDecimal(pct), null, null);
    }

    static PersonInput fixed(String name, long minor) {
        return new PersonInput(name, null, minor, null);
    }

    static PersonInput shares(String name, int shares) {
        return new PersonInput(name, null, null, shares);
    }

    static List<Long> amounts(Allocation a) {
        return a.people().stream().map(PersonShare::amountMinor).toList();
    }

    @Test
    void equalSharesTheExpenseWithTheUserAndTheOddCentsAreTheUsers() {
        var even = SplitCalculator.allocate(30_000, Mode.EQUAL, null, List.of(person("Ana"), person("Dan")));
        assertThat(amounts(even)).containsExactly(10_000L, 10_000L);
        assertThat(even.mineMinor()).isEqualTo(10_000);

        var odd = SplitCalculator.allocate(10_000, Mode.EQUAL, null, List.of(person("Ana"), person("Dan")));
        assertThat(amounts(odd)).containsExactly(3_333L, 3_333L);
        assertThat(odd.mineMinor()).isEqualTo(3_334);
        assertThat(odd.othersMinor() + odd.mineMinor()).isEqualTo(10_000);
    }

    @Test
    void percentagesAreEachPersonsPartAndTheRestIsTheUsers() {
        var a = SplitCalculator.allocate(30_000, Mode.PERCENT, null, List.of(percent("Ana", "50"), percent("Dan", "25")));
        assertThat(amounts(a)).containsExactly(15_000L, 7_500L);
        assertThat(a.mineMinor()).isEqualTo(7_500);
        assertThat(a.people().getFirst().percent()).isEqualByComparingTo("50");

        var all = SplitCalculator.allocate(1_001, Mode.PERCENT, null, List.of(percent("Ana", "100")));
        assertThat(all.mineMinor()).isZero(); // "they owe it all": the user only paid

        var thirds = SplitCalculator.allocate(1_000, Mode.PERCENT, null,
                List.of(percent("Ana", "33.33"), percent("Dan", "33.33")));
        assertThat(amounts(thirds)).containsExactly(333L, 333L); // 333.3 rounded down
        assertThat(thirds.mineMinor()).isEqualTo(334);
    }

    @Test
    void fixedAmountsAreTakenAsTheyAre() {
        var a = SplitCalculator.allocate(30_000, Mode.FIXED, null, List.of(fixed("Ana", 12_000), fixed("Dan", 8_050)));
        assertThat(amounts(a)).containsExactly(12_000L, 8_050L);
        assertThat(a.mineMinor()).isEqualTo(9_950);
        assertThat(a.people().getFirst().fixedMinor()).isEqualTo(12_000);
    }

    @Test
    void sharesWeighEveryoneTheUserIncluded() {
        // A couple (2) and two friends (1 each): 10,000 → 5,000 for the couple, 2,500 each.
        var a = SplitCalculator.allocate(10_000, Mode.SHARES, 1, List.of(shares("Ana and Dan", 2), shares("Ion", 1)));
        assertThat(amounts(a)).containsExactly(5_000L, 2_500L);
        assertThat(a.mineMinor()).isEqualTo(2_500);

        var onlyPaid = SplitCalculator.allocate(10_000, Mode.SHARES, 0, List.of(shares("Ana", 1), shares("Dan", 1)));
        assertThat(amounts(onlyPaid)).containsExactly(5_000L, 5_000L);
        assertThat(onlyPaid.mineMinor()).isZero();

        var byDefault = SplitCalculator.allocate(9_000, Mode.SHARES, null, List.of(shares("Ana", 2)));
        assertThat(byDefault.mineMinor()).as("your own weight is 1 by default").isEqualTo(3_000);
    }

    @Test
    void namesAreTrimmedAndKeptInOrder() {
        var a = SplitCalculator.allocate(30_000, Mode.EQUAL, null, List.of(person("  Ana   Maria "), person("Dan")));
        assertThat(a.people()).extracting(PersonShare::name).containsExactly("Ana Maria", "Dan");
    }

    @Test
    void splitsThatDoNotAddUpAreRefused() {
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.PERCENT, null,
                List.of(percent("Ana", "60"), percent("Dan", "50")))).hasMessageContaining("more than 100%");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.FIXED, null,
                List.of(fixed("Ana", 20_000), fixed("Dan", 10_001)))).hasMessageContaining("more than the expense");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.PERCENT, null, List.of(percent("Ana", "10.555"))))
                .hasMessageContaining("two decimals");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.PERCENT, null, List.of(person("Ana"))))
                .hasMessageContaining("percentage");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.FIXED, null, List.of(fixed("Ana", 0))))
                .hasMessageContaining("above 0");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.SHARES, -1, List.of(shares("Ana", 1))))
                .hasMessageContaining("Your shares");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.EQUAL, null, List.of(person("Ana"), person(" ana "))))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.EQUAL, null, List.of(person(" "))))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, Mode.EQUAL, null, List.of()))
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> SplitCalculator.allocate(1, Mode.EQUAL, null, List.of(person("Ana"))))
                .hasMessageContaining("round to zero");
        assertThatThrownBy(() -> SplitCalculator.allocate(30_000, null, null, List.of(person("Ana"))))
                .hasMessageContaining("Choose how");
    }
}
