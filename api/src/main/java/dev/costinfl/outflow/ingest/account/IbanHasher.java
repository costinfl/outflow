package dev.costinfl.outflow.ingest.account;

import dev.costinfl.outflow.ingest.parse.Iban;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 of an IBAN's electronic form. The key lives outside the database, so a DB dump alone cannot reverse it. */
public final class IbanHasher {

    public static final int MIN_KEY_BYTES = 32;

    private final SecretKeySpec key;

    public IbanHasher(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("IBAN HMAC key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "HmacSHA256");
    }

    public byte[] hash(Iban iban) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(iban.value().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
