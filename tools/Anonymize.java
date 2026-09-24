/*
 * Outflow statement anonymizer. Runs locally, needs only Java 21, no dependencies, no network.
 *
 *   java tools/Anonymize.java --in ~/Downloads/export.csv --out samples/mybank/export-2026.csv \
 *        [--names ~/names.txt] [--seed "a private phrase"] [--encoding windows-1250]
 *
 * Replaces personal data and leaves every other byte of the file as it was (encoding, delimiter, quoting, preamble,
 * line endings), so the result is a faithful parser test. Amounts, dates and merchant text are kept.
 *
 *   IBANs            -> checksum-valid fakes with bank code ANON, same country and length
 *   card numbers     -> full PANs and the digits next to masks ("****4412", "card 4412") get fake digits
 *   names            -> the account holder ("Titular: ...") and every name in --names become PERSON_1, PERSON_2, ...
 *   CNP              -> 13 fake digits
 *   emails, phones   -> person1@example.invalid, 0700xxxxxx
 *   long references  -> digit runs of 10+ get fake digits of the same length
 *
 * With the same --seed, the same original always maps to the same fake (across files and runs), so overlapping
 * exports still overlap. Without a seed a random one is used. No mapping is ever written anywhere.
 *
 * Automatic detection cannot find every personal detail. ALWAYS read the report and the output before sharing.
 */

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Anonymize {

    static final String FAKE_BANK = "ANON";

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = options(args);
        if (!opt.containsKey("in") || !opt.containsKey("out")) {
            System.err.println("usage: java tools/Anonymize.java --in <raw export> --out <anonymized file> "
                    + "[--names <file, one name per line>] [--seed <private phrase>] [--encoding <charset>]");
            System.exit(2);
        }
        Path in = Path.of(opt.get("in")).toAbsolutePath().normalize();
        Path out = Path.of(opt.get("out")).toAbsolutePath().normalize();
        if (in.equals(out)) {
            fail("--out must differ from --in; the raw export is never modified");
        }
        for (Path dir = in.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                System.err.println("WARNING: the raw export is inside a git repository (" + dir
                        + "). Move it outside so it can never be committed.");
                break;
            }
        }

        byte[] raw = Files.readAllBytes(in);
        Charset charset = opt.containsKey("encoding") ? Charset.forName(opt.get("encoding")) : detect(raw);
        String text = decode(raw, charset);

        String seed = opt.get("seed");
        if (seed == null) {
            byte[] r = new byte[24];
            new SecureRandom().nextBytes(r);
            seed = HexFormat.of().formatHex(r);
            System.err.println("NOTE: no --seed given, using a random one: fakes will differ between runs and files."
                    + " Pass the same private --seed to keep overlapping exports consistent.");
        }
        List<String> names = new ArrayList<>();
        if (opt.containsKey("names")) {
            for (String line : Files.readAllLines(Path.of(opt.get("names")), StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.strip().startsWith("#")) {
                    names.add(line.strip());
                }
            }
        }

        var anonymizer = new Anonymizer(seed, names);
        String result = anonymizer.run(text);

        Files.createDirectories(out.getParent());
        Files.write(out, encode(result, charset));

        System.out.println(anonymizer.report(charset, out));
    }

    /** The replacements, in order. Package-private pieces are exercised by the repository's tests. */
    static final class Anonymizer {

        private static final Pattern IBAN = Pattern.compile("\\b([A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30})\\b");
        private static final Pattern PAN = Pattern.compile("(?<![\\dA-Za-z])(\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{1,7})(?!\\d)");
        private static final Pattern MASK_DIGITS = Pattern.compile("(?<![A-Za-z0-9])((?:\\d{0,6})[*X•]{2,}[ *X•]*)(\\d{4})(?!\\d)");
        private static final Pattern CARD_DIGITS = Pattern.compile("(?i)(\\bcard\\s*(?:nr\\.?|no\\.?)?\\s*)(\\d{4})(?!\\d)");
        private static final Pattern CNP = Pattern.compile("(?<!\\d)([1-8]\\d{12})(?!\\d)");
        private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        private static final Pattern PHONE = Pattern.compile("(?<![\\d+])(?:\\+40|0040|0)\\s?7\\d{2}[\\s.-]?\\d{3}[\\s.-]?\\d{3}(?!\\d)");
        private static final Pattern LONG_DIGITS = Pattern.compile("(?<![\\p{L}\\d.,])(\\d{10,})(?![\\d.,]\\d)");
        private static final Pattern HOLDER = Pattern.compile(
                "(?im)^([ \\t\"]*(?:titular(?: cont)?|nume(?: client)?|client|account holder|holder|name)[ \\t\"]*[:;,\\t]+[ \\t\"]*)([^;,\\t\"\\r\\n]+)");
        private static final Pattern TRANSFERISH = Pattern.compile(
                "(?i)transfer|catre|către|de la|beneficiar|ordonator|platitor|plătitor|p2p|revolut|incasare|încasare");

        private final Mac mac;
        private final List<String> names;
        private final Map<String, String> personOf = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new TreeMap<>();
        private final Map<String, List<String>> examples = new TreeMap<>();
        private final Map<String, String> emailOf = new HashMap<>();
        private String output = "";

        Anonymizer(String seed, List<String> names) throws Exception {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.names = new ArrayList<>(names);
        }

        String run(String text) {
            String s = text;
            // The holder's name is also looked for everywhere else (e.g. transfers to one's own accounts).
            Matcher h = HOLDER.matcher(s);
            while (h.find()) {
                String name = h.group(2).strip();
                if (!name.isEmpty() && Iban.find(name) == null && names.stream().noneMatch(n -> n.equalsIgnoreCase(name))) {
                    names.add(0, name);
                }
            }
            s = replace(s, IBAN, "IBAN", m -> {
                String original = m.group(1);
                return Iban.valid(original) ? fakeIban(original) : null;
            });
            s = replace(s, PAN, "card number", m -> {
                String digits = m.group(1).replaceAll("[ -]", "");
                return digits.length() >= 13 && digits.length() <= 19 && luhn(digits)
                        ? sameShape(m.group(1), digits("pan", digits, digits.length())) : null;
            });
            s = replace(s, MASK_DIGITS, "card digits", m -> m.group(1) + digits("card4", m.group(2), 4));
            s = replace(s, CARD_DIGITS, "card digits", m -> m.group(1) + digits("card4", m.group(2), 4));
            s = replace(s, CNP, "CNP", m -> cnpValid(m.group(1)) ? "0" + digits("cnp", m.group(1), 12) : null);
            s = replace(s, EMAIL, "email", m -> emailOf.computeIfAbsent(m.group().toLowerCase(Locale.ROOT),
                    e -> "person" + (emailOf.size() + 1) + "@example.invalid"));
            s = replace(s, PHONE, "phone", m -> "0700" + digits("phone", m.group().replaceAll("\\D", ""), 6));
            for (String name : List.copyOf(names)) {
                String person = personOf.computeIfAbsent(fold(name), k -> "PERSON_" + (personOf.size() + 1));
                s = replace(s, namePattern(name), "name", m -> person);
                String[] words = name.strip().split("\\s+");
                if (words.length == 2) {
                    s = replace(s, namePattern(words[1] + " " + words[0]), "name", m -> person);
                }
            }
            s = replace(s, LONG_DIGITS, "long reference", m -> digits("ref", m.group(1), m.group(1).length()));
            output = s;
            return s;
        }

        private String replace(String text, Pattern p, String kind, Function<Matcher, String> replacement) {
            Matcher m = p.matcher(text);
            var out = new StringBuilder();
            while (m.find()) {
                String r = replacement.apply(m);
                if (r == null || r.equals(m.group())) {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                counts.merge(kind, 1, Integer::sum);
                var ex = examples.computeIfAbsent(kind, k -> new ArrayList<>());
                String shown = peek(m.group()) + "  ->  " + r.strip();
                if (ex.size() < 5 && !ex.contains(shown)) {
                    ex.add(shown);
                }
                m.appendReplacement(out, Matcher.quoteReplacement(r));
            }
            return m.appendTail(out).toString();
        }

        /** Enough of the original to recognise it in the report, not enough to leak it. */
        private static String peek(String original) {
            String s = original.strip();
            return s.length() <= 6 ? s.charAt(0) + "..." : s.substring(0, 3) + "..." + s.substring(s.length() - 2);
        }

        String report(Charset charset, Path out) {
            var r = new StringBuilder();
            r.append("Anonymized ").append(out).append(" (").append(charset.name()).append(")\n\nReplaced:\n");
            if (counts.isEmpty()) {
                r.append("  nothing\n");
            }
            counts.forEach((kind, n) -> {
                r.append(String.format("  %-15s %d%n", kind, n));
                examples.getOrDefault(kind, List.of()).forEach(e -> r.append("      ").append(e).append('\n'));
            });
            if (!personOf.isEmpty()) {
                r.append("\nNames looked for: ").append(personOf.size())
                        .append(" (holder + --names; 'First Last' also matched as 'Last First')\n");
            }
            var leftovers = new ArrayList<String>();
            Matcher ib = IBAN.matcher(output);
            while (ib.find()) {
                if (!ib.group(1).replace(" ", "").substring(4, 8).equals(FAKE_BANK)) {
                    leftovers.add("IBAN-like text kept (checksum invalid?): " + peek(ib.group(1)));
                }
            }
            Matcher d = Pattern.compile("(?<![\\d.,])\\d{6,9}(?![\\d.,]\\d)").matcher(output);
            int digitRuns = 0;
            while (d.find()) {
                digitRuns++;
            }
            if (digitRuns > 0) {
                leftovers.add(digitRuns + " digit runs of 6-9 digits kept (store/terminal/invoice numbers?)");
            }
            var transfers = output.lines().filter(l -> TRANSFERISH.matcher(l).find()).limit(40).toList();
            r.append("\nReview before sharing:\n");
            leftovers.forEach(l -> r.append("  - ").append(l).append('\n'));
            if (!transfers.isEmpty()) {
                r.append("  - these lines look like transfers; check them for names of people (add them to --names"
                        + " and run again):\n");
                transfers.forEach(l -> r.append("      ").append(l).append('\n'));
            }
            r.append("\nOpen the output file and read it before committing it. Nothing is ever uploaded by this tool.\n");
            return r.toString();
        }

        /** Deterministic fake digits for an original value (HMAC with the private seed). */
        String digits(String kind, String original, int length) {
            byte[] h = mac.doFinal((kind + "|" + original).getBytes(StandardCharsets.UTF_8));
            String d = new BigInteger(1, h).toString(10);
            while (d.length() < length) {
                d += new BigInteger(1, mac.doFinal(d.getBytes(StandardCharsets.UTF_8))).toString(10);
            }
            return d.substring(0, length);
        }

        String fakeIban(String original) {
            String compact = original.replace(" ", "").toUpperCase(Locale.ROOT);
            String country = compact.substring(0, 2);
            String bban = FAKE_BANK + digits("iban", compact, compact.length() - 8);
            int check = 98 - Iban.mod97(bban + country + "00");
            String fake = country + String.format("%02d", check) + bban;
            return original.contains(" ") ? fake.replaceAll("(.{4})(?!$)", "$1 ") : fake;
        }

        private static String sameShape(String shape, String digits) {
            var out = new StringBuilder();
            int i = 0;
            for (char c : shape.toCharArray()) {
                out.append(Character.isDigit(c) ? digits.charAt(i++) : c);
            }
            return out.toString();
        }

        /** A name matched as whole words, case- and diacritic-insensitive, any whitespace between words. */
        static Pattern namePattern(String name) {
            var p = new StringBuilder("(?<![\\p{L}\\d])");
            String[] words = name.strip().split("\\s+");
            for (int w = 0; w < words.length; w++) {
                if (w > 0) {
                    p.append("[\\s.,-]+");
                }
                for (char c : fold(words[w]).toCharArray()) {
                    p.append(switch (c) {
                        case 'A' -> "[AĂÂaăâ]";
                        case 'I' -> "[IÎiî]";
                        case 'S' -> "[SȘŞsșş]";
                        case 'T' -> "[TȚŢtțţ]";
                        default -> Character.isLetter(c)
                                ? "[" + c + Character.toLowerCase(c) + "]"
                                : Pattern.quote(String.valueOf(c));
                    });
                }
            }
            return Pattern.compile(p.append("(?![\\p{L}\\d])").toString());
        }

        static String fold(String s) {
            return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
        }

        static boolean luhn(String digits) {
            int sum = 0;
            for (int i = 0; i < digits.length(); i++) {
                int d = digits.charAt(digits.length() - 1 - i) - '0';
                if (i % 2 == 1) {
                    d = d * 2 > 9 ? d * 2 - 9 : d * 2;
                }
                sum += d;
            }
            return sum % 10 == 0;
        }

        static boolean cnpValid(String cnp) {
            String weights = "279146358279";
            int sum = 0;
            for (int i = 0; i < 12; i++) {
                sum += (cnp.charAt(i) - '0') * (weights.charAt(i) - '0');
            }
            int control = sum % 11 == 10 ? 1 : sum % 11;
            return control == cnp.charAt(12) - '0';
        }
    }

    /** Just enough IBAN logic for this standalone tool (the app has its own in ingest.parse.Iban). */
    static final class Iban {

        static boolean valid(String text) {
            String c = text.replace(" ", "").toUpperCase(Locale.ROOT);
            return c.matches("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}") && mod97(c.substring(4) + c.substring(0, 4)) == 1;
        }

        static int mod97(String s) {
            var digits = new StringBuilder();
            for (char ch : s.toCharArray()) {
                digits.append(Character.isDigit(ch) ? String.valueOf(ch) : String.valueOf(ch - 'A' + 10));
            }
            return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
        }

        static String find(String text) {
            Matcher m = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9 ]{11,}").matcher(text.toUpperCase(Locale.ROOT));
            return m.find() && valid(m.group()) ? m.group() : null;
        }
    }

    static Charset detect(byte[] raw) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("windows-1250"); // older Romanian bank exports
        }
    }

    static String decode(byte[] raw, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString();
    }

    static byte[] encode(String text, Charset charset) throws CharacterCodingException {
        ByteBuffer b = charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    static Map<String, String> options(String[] args) {
        var opt = new HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                fail("unexpected argument " + args[i]);
            }
            opt.put(args[i].substring(2), args[i + 1]);
        }
        return opt;
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
