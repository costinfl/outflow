package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.review.CategorySuggester.Known;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CP7.3: suggestions only from the user's own answers for similar names; nothing when they disagree. */
class CategorySuggesterTest {

    static final Set<String> FILLER = Set.of("PLATA", "FACTURA");

    static Known groceries(String key, String name) {
        return new Known(key, name, 1, "Groceries");
    }

    static Known shopping(String key, String name) {
        return new Known(key, name, 11, "Shopping");
    }

    static CategorySuggester suggester(String... extraKeys) {
        var keys = new ArrayList<>(List.of("MEGA IMAGE CLUJ", "MEGA IMAGE", "MEGA STORE", "PROFI CITY", "PROFI"));
        keys.addAll(List.of(extraKeys));
        return new CategorySuggester(keys, FILLER);
    }

    @Test
    void twoMerchantsThatAgreeSuggestTheirCategory() {
        var known = List.of(groceries("MEGA IMAGE CLUJ", "Mega Image Cluj"), groceries("MEGA IMAGE", "Mega Image"));

        var s = suggester().suggest("MEGA IMAGE BRASOV", known).orElseThrow();
        assertThat(s.categoryId()).isEqualTo(1);
        assertThat(s.categoryName()).isEqualTo("Groceries");
        assertThat(s.because()).containsExactly("Mega Image Cluj", "Mega Image");
    }

    @Test
    void merchantsThatDisagreeSuggestNothing() {
        var known = List.of(groceries("MEGA IMAGE", "Mega Image"), groceries("MEGA IMAGE CLUJ", "Mega Image Cluj"),
                shopping("MEGA STORE", "Mega Store"), shopping("MEGA STORE NORD", "Mega Store Nord"));

        assertThat(suggester().suggest("MEGA OUTLET", known)).isEmpty(); // 2 groceries, 2 shopping: a tie
    }

    @Test
    void oneMerchantOfTheSameBrandIsEnough() {
        assertThat(suggester().suggest("PROFI BRASOV", List.of(groceries("PROFI CITY", "Profi City"))))
                .hasValueSatisfying(s -> assertThat(s.because()).containsExactly("Profi City"));
        // One merchant sharing only a later word is not.
        assertThat(suggester().suggest("SUPER PROFI", List.of(groceries("PROFI CITY", "Profi City")))).isEmpty();
    }

    @Test
    void commonWordsFillerAndNumbersNameNoPayee() {
        var keys = new ArrayList<String>();
        for (int i = 0; i < 11; i++) {
            keys.add("SHOP" + (char) ('A' + i) + " BUCURESTI");
        }
        var s = new CategorySuggester(keys, FILLER);
        assertThat(s.words("ALPHA BUCURESTI PLATA 1234 X2Y SRL")).containsExactly("ALPHA"); // SRL: 3 letters
        var known = List.of(groceries("SHOPA BUCURESTI", "Shop A"), groceries("SHOPB BUCURESTI", "Shop B"));
        assertThat(s.suggest("ZETA BUCURESTI", known)).isEmpty();
    }

    @Test
    void peopleAndTheMerchantItselfNeverCount() {
        var known = List.of(groceries("PERSON_3", "Person_3"), groceries("PERSON_4", "Person_4"));
        assertThat(suggester().suggest("PERSON_5", known)).isEmpty();
        assertThat(suggester().suggest("PROFI", List.of(groceries("PROFI", "Profi")))).isEmpty();
    }
}
