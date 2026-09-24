package dev.costinfl.outflow.ingest.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.ingest.parse.Iban;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IbanKeyConfigTest {

    static final Iban IBAN = Iban.parse("RO49AAAA1B31007593840000").orElseThrow();

    @TempDir
    Path dir;

    @Test
    void generatesAKeyOnceAndReusesIt() throws Exception {
        var config = new IbanKeyConfig();

        var first = config.ibanHasher("", dir);
        var second = config.ibanHasher("", dir);

        Path file = dir.resolve(IbanKeyConfig.KEY_FILE);
        assertThat(file).exists();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(second.hash(IBAN)).isEqualTo(first.hash(IBAN)); // stable across restarts
    }

    @Test
    void configuredKeyWinsAndNoFileIsWritten() throws Exception {
        var hasher = new IbanKeyConfig().ibanHasher("dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXk=", dir);

        assertThat(hasher.hash(IBAN)).hasSize(32);
        assertThat(dir.resolve(IbanKeyConfig.KEY_FILE)).doesNotExist();
    }

    @Test
    void differentKeysGiveDifferentHashesAndShortKeysAreRefused() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        b[0] = 1;
        assertThat(new IbanHasher(a).hash(IBAN)).isNotEqualTo(new IbanHasher(b).hash(IBAN));
        assertThatThrownBy(() -> new IbanHasher(new byte[16])).hasMessageContaining("at least 32 bytes");
    }
}
