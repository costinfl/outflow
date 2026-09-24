package dev.costinfl.outflow.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.Iban;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if anything under samples/ looks like un-anonymized personal data: a valid IBAN that is not an
 * Anonymize fake (bank code ANON) or the documented example, a valid CNP, a real-looking email, a full card number.
 */
class SamplesGuardTest {

    static final Path SAMPLES = Path.of("..", "samples");
    static final String DOCUMENTED_EXAMPLE = "RO49AAAA1B31007593840000";

    @Test
    void samplesHoldNoRealPersonalData() throws IOException {
        var findings = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(SAMPLES)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                // Latin-1 maps every byte to one char: enough for the ASCII patterns below in any encoding.
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                findings.addAll(scan(SAMPLES.relativize(file).toString(), text));
            }
        }
        assertThat(findings).as("run tools/Anonymize.java on these files").isEmpty();
    }

    static List<String> scan(String name, String text) {
        var out = new ArrayList<String>();
        var iban = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b").matcher(text);
        while (iban.find()) {
            Iban.parse(iban.group()).filter(i -> !i.value().equals(DOCUMENTED_EXAMPLE))
                    .filter(i -> !i.value().substring(4, 8).equals("ANON"))
                    .ifPresent(i -> out.add(name + ": IBAN " + i.masked()));
        }
        var cnp = Pattern.compile("(?<!\\d)[1-8]\\d{12}(?!\\d)").matcher(text);
        while (cnp.find()) {
            if (cnpValid(cnp.group())) {
                out.add(name + ": CNP-like number");
            }
        }
        var email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(text);
        while (email.find()) {
            if (!email.group().endsWith("@example.invalid")) {
                out.add(name + ": email");
            }
        }
        var pan = Pattern.compile("(?<![\\dA-Za-z])(?:\\d{13,19}|\\d{4}(?:[ -]\\d{4}){2}[ -]\\d{1,7})(?!\\d)").matcher(text);
        while (pan.find()) {
            String d = pan.group().replaceAll("\\D", "");
            if (d.length() >= 13 && d.length() <= 19 && luhn(d) && !d.startsWith("0")) {
                out.add(name + ": card-number-like digits");
            }
        }
        return out;
    }

    @Test
    void theGuardCatchesWhatItShould() {
        assertThat(scan("x", "IBAN RO49AAAA1B31007593840000 is the doc example")).isEmpty();
        assertThat(scan("x", "GB82WEST12345698765432")).singleElement().asString().contains("IBAN");
        assertThat(scan("x", "cnp 1800101221144")).singleElement().asString().contains("CNP");
        assertThat(scan("x", "a@b.ro person1@example.invalid")).singleElement().asString().contains("email");
        assertThat(scan("x", "4111 1111 1111 1111")).singleElement().asString().contains("card");
        assertThat(scan("x", "2026-02-01,LIDL 1234 BUCURESTI,-45.10,RON")).isEmpty();
    }

    static boolean cnpValid(String cnp) {
        String w = "279146358279";
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (cnp.charAt(i) - '0') * (w.charAt(i) - '0');
        }
        return (sum % 11 == 10 ? 1 : sum % 11) == cnp.charAt(12) - '0';
    }

    static boolean luhn(String d) {
        int sum = 0;
        for (int i = 0; i < d.length(); i++) {
            int x = d.charAt(d.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                x = x * 2 > 9 ? x * 2 - 9 : x * 2;
            }
            sum += x;
        }
        return sum % 10 == 0;
    }
}
