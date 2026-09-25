package dev.costinfl.outflow.merchant.normalize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Step 2: strip leading payment-channel words ("CUMPARARE POS", "PLATA CARD", "PAYPAL *", …) and payment-processor
 * prefixes ("PAYU*EMAG.RO" is eMAG, "MOBILPAY*AMPARCAT" is AmParcat), repeatedly.
 */
public final class ChannelPrefix implements MerchantStep {

    /** Longest first, so "CUMPARARE POS" wins over "POS". */
    static final List<String> PREFIXES = List.of(
            "CUMPARARE POS", "CUMPARARE ONLINE", "PLATA LA POS", "PLATA POS", "PLATA CARD", "PLATA ONLINE",
            "TRANSFER CATRE", "TRANSFER DIN", "TRANZACTIE CARD", "TRANZACTIE", "CARD PAYMENT", "CARD PURCHASE",
            "PAYPAL *", "PAYPAL*", "PAYPAL", "SQ *", "SUMUP *", "SUMUP*",
            // Romanian card processors and resellers, seen in real ING exports
            "PAYU *", "PAYU*", "MOBILPAY *", "MOBILPAY*", "NETOPIA *", "NETOPIA*", "NYX *", "NYX*", "MPY *", "MPY*",
            "EP *", "EP*", "PADDLE.NET *", "PADDLE.NET*",
            "INCASARE", "TRANSFER", "PLATA", "ONLINE", "POS");

    private static final List<Pattern> PATTERNS = PREFIXES.stream()
            .map(p -> Pattern.compile("^" + Pattern.quote(p) + (p.endsWith("*") ? "\\s*" : "(?:\\s+|$)")))
            .toList();

    @Override
    public String apply(String text) {
        String s = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern p : PATTERNS) {
                var m = p.matcher(s);
                if (m.find()) {
                    s = s.substring(m.end());
                    changed = true;
                    break;
                }
            }
        }
        return Words.collapse(s);
    }
}
