package dev.costinfl.outflow.merchant.normalize;

import java.util.regex.Pattern;

final class Words {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Words() {}

    static String collapse(String s) {
        return SPACES.matcher(s).replaceAll(" ").strip();
    }
}
