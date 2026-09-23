package dev.costinfl.outflow.ingest.identity;

import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-derived, file-independent identity keys (DESIGN: Transaction identity and idempotency).
 *
 * <ol>
 *   <li>Bank reference present: {@code ref:<reference>}.</li>
 *   <li>Otherwise {@code key_v1:<sha256(account|booking date|amount minor|currency|description_norm)>#n}, where
 *       {@code n} numbers rows sharing that hash on that date, sorted by (value date, raw description, row no).</li>
 * </ol>
 *
 * The occurrence index depends only on the rows of one date in one file, so re-uploading the same or an overlapping
 * period reproduces the same keys.
 */
public final class IdentityKeys {

    public record IdentifiedRow(ParsedRow row, String identityKey, String descriptionNorm) {}

    private static final Comparator<ParsedRow> OCCURRENCE_ORDER = Comparator
            .comparing((ParsedRow r) -> r.valueDate().orElse(LocalDate.MIN))
            .thenComparing(ParsedRow::description)
            .thenComparingInt(ParsedRow::rowNo);

    private IdentityKeys() {}

    /** Keys for every row of one file, in the rows' original order. */
    public static List<IdentifiedRow> assign(long accountId, List<ParsedRow> rows) {
        var keys = new HashMap<ParsedRow, String>();
        var norms = new HashMap<ParsedRow, String>();
        var byContent = new LinkedHashMap<String, List<ParsedRow>>();
        var byReference = new HashMap<String, ParsedRow>();

        for (ParsedRow row : rows) {
            String norm = HashNormalizer.normalize(row.description());
            norms.put(row, norm);
            if (row.reference().isPresent()) {
                String ref = row.reference().get().strip();
                var earlier = byReference.putIfAbsent(ref, row);
                if (earlier != null && !sameRecord(earlier, row, norms)) {
                    throw StatementParseException.atRow(row.rowNo(),
                            "bank reference repeats row " + earlier.rowNo() + " with different content");
                }
                keys.put(row, "ref:" + ref);
            } else {
                byContent.computeIfAbsent(contentHash(accountId, row, norm), h -> new ArrayList<>()).add(row);
            }
        }
        // The content hash already includes the booking date, so each group is "same hash on the same date".
        byContent.forEach((hash, group) -> {
            var ordered = group.stream().sorted(OCCURRENCE_ORDER).toList();
            for (int i = 0; i < ordered.size(); i++) {
                keys.put(ordered.get(i), HashNormalizer.VERSION + ":" + hash + "#" + (i + 1));
            }
        });
        return rows.stream().map(r -> new IdentifiedRow(r, keys.get(r), norms.get(r))).toList();
    }

    static String contentHash(long accountId, ParsedRow row, String descriptionNorm) {
        String material = String.join("|",
                Long.toString(accountId),
                row.bookingDate().toString(),
                Long.toString(row.amountMinor()),
                row.currency(),
                descriptionNorm);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean sameRecord(ParsedRow a, ParsedRow b, Map<ParsedRow, String> norms) {
        return a.bookingDate().equals(b.bookingDate())
                && a.amountMinor() == b.amountMinor()
                && a.currency().equals(b.currency())
                && Objects.equals(norms.get(a), norms.get(b));
    }
}
