package dev.costinfl.outflow.ingest.account;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The IBAN HMAC key: {@code outflow.iban-hmac-key} (base64, env {@code OUTFLOW_IBAN_HMAC_KEY}) when set; otherwise a
 * random key generated once into {@code <outflow.data-dir>/iban-hmac.key}. Losing the key means existing accounts are
 * no longer recognised by IBAN (imports then ask for the account), so back it up with the data directory.
 */
@Configuration
public class IbanKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(IbanKeyConfig.class);
    static final String KEY_FILE = "iban-hmac.key";

    @Bean
    IbanHasher ibanHasher(
            @Value("${outflow.iban-hmac-key:}") String configuredKey,
            @Value("${outflow.data-dir:./data}") Path dataDir) throws IOException {
        if (!configuredKey.isBlank()) {
            return new IbanHasher(Base64.getDecoder().decode(configuredKey.strip()));
        }
        return new IbanHasher(loadOrCreate(dataDir.resolve(KEY_FILE)));
    }

    static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            return Base64.getDecoder().decode(Files.readString(file).strip());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] key = new byte[IbanHasher.MIN_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        try {
            Files.writeString(Files.createFile(file), Base64.getEncoder().encodeToString(key) + "\n");
        } catch (FileAlreadyExistsException e) {
            return loadOrCreate(file); // another instance won the race
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system (Windows): rely on the directory's ACLs
        }
        log.info("Generated a new IBAN HMAC key at {}; back it up together with the database", file.toAbsolutePath());
        return key;
    }
}
