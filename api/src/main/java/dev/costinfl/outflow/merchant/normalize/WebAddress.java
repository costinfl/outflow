package dev.costinfl.outflow.merchant.normalize;

import java.util.regex.Pattern;

/** Step 3a: a web address becomes its name: {@code NETFLIX.COM} → NETFLIX, {@code BOLT.EU/R/123} → BOLT. */
public final class WebAddress implements MerchantStep {

    private static final Pattern ADDRESS = Pattern.compile(
            "(?:WWW\\.)?([A-Z0-9][A-Z0-9-]*)\\.(?:COM|RO|EU|DE|NET|ORG|IO|CO\\.UK|UK|NL|FR|IT|ES|IE|APP|TV|SHOP)(?:/\\S*)?(?=\\s|$|\\*)");

    @Override
    public String apply(String text) {
        return Words.collapse(ADDRESS.matcher(text).replaceAll("$1"));
    }
}
