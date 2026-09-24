package dev.costinfl.outflow.merchant.normalize;

import java.util.List;
import java.util.Set;

/**
 * Step 4: strip trailing country codes and city names ("… BUCURESTI RO", "… STOCKHOLM SE"), repeatedly, but never
 * the first word: "ORANGE ROMANIA" becomes ORANGE, "ROMANIA" alone stays.
 */
public final class TrailingLocation implements MerchantStep {

    static final Set<String> COUNTRIES = Set.of(
            "RO", "ROU", "ROMANIA", "SE", "NL", "IE", "EE", "DE", "LU", "GB", "UK", "US", "USA", "FR", "IT", "ES",
            "HU", "BG", "AT", "PL", "CZ", "BE", "CH", "DK", "FI", "PT", "GR", "MD", "CY", "MT", "LT", "LV", "SK", "SI");

    static final Set<String> CITIES = Set.of(
            "BUCURESTI", "BUCHAREST", "SECTOR", "CLUJ-NAPOCA", "CLUJ", "NAPOCA", "TIMISOARA", "IASI", "CONSTANTA",
            "BRASOV", "SIBIU", "ORADEA", "CRAIOVA", "GALATI", "PLOIESTI", "ARAD", "PITESTI", "BACAU", "SUCEAVA",
            "VOLUNTARI", "OTOPENI", "POPESTI-LEORDENI", "BRAGADIRU", "CHIAJNA", "AMSTERDAM", "STOCKHOLM", "TALLINN",
            "DUBLIN", "LONDON", "LUXEMBOURG", "BERLIN", "PARIS", "MUNCHEN", "MUNICH", "VIENNA", "WIEN", "BUDAPEST",
            "SOFIA", "PRAGUE", "WARSAW", "MADRID", "BARCELONA", "ROMA", "ROME", "MILANO", "LISBOA", "LISBON");

    @Override
    public String apply(String text) {
        var words = new java.util.ArrayList<>(List.of(text.split(" ")));
        while (words.size() > 1 && isLocation(words.getLast())) {
            words.removeLast();
        }
        return String.join(" ", words);
    }

    private static boolean isLocation(String word) {
        return COUNTRIES.contains(word) || CITIES.contains(word);
    }
}
