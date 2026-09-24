package dev.costinfl.outflow.ingest.account;

import dev.costinfl.outflow.ingest.parse.AccountHint;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts of the (single, seeded) household. IBANs arrive as {@link AccountHint}s and are stored hashed + masked. */
@Service
public class AccountService {

    public static final long HOUSEHOLD = 1;
    public static final long USER = 1;

    public record Resolved(Account account, boolean created) {}

    private static final RowMapper<Account> ROW = (rs, i) -> new Account(
            rs.getLong("id"), rs.getString("name"), rs.getString("iban_masked"),
            rs.getString("currency"), Account.Kind.valueOf(rs.getString("kind")));

    private final JdbcTemplate jdbc;
    private final IbanHasher hasher;

    public AccountService(JdbcTemplate jdbc, IbanHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    public List<Account> list() {
        return jdbc.query("SELECT * FROM account WHERE household_id = ? ORDER BY id", ROW, HOUSEHOLD);
    }

    public Optional<Account> find(long id) {
        return jdbc.query("SELECT * FROM account WHERE household_id = ? AND id = ?", ROW, HOUSEHOLD, id)
                .stream().findFirst();
    }

    @Transactional
    public Account create(String name, String currency, Account.Kind kind) {
        long id = jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, currency, kind)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, HOUSEHOLD, USER, name, currency, kind.name());
        return find(id).orElseThrow();
    }

    /** The account with this IBAN, created on first sight (DESIGN: First-run flow, "Accounts detected"). */
    @Transactional
    public Resolved resolve(AccountHint hint, String fallbackCurrency) {
        byte[] hash = hasher.hash(hint.iban());
        var existing = jdbc.query("SELECT * FROM account WHERE household_id = ? AND iban_hash = ?", ROW, HOUSEHOLD, hash);
        if (!existing.isEmpty()) {
            return new Resolved(existing.getFirst(), false);
        }
        String masked = hint.iban().masked();
        long id = jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, iban_hash, iban_masked, currency, kind)
                VALUES (?, ?, ?, ?, ?, ?, 'CURRENT') RETURNING id""", Long.class,
                HOUSEHOLD, USER, "Account ••" + masked.substring(masked.length() - 4), hash, masked,
                hint.currency().orElse(fallbackCurrency));
        return new Resolved(find(id).orElseThrow(), true);
    }

    /** True when the account has an IBAN on record and it is a different one. */
    public boolean contradicts(long accountId, AccountHint hint) {
        byte[] stored = jdbc.queryForObject("SELECT iban_hash FROM account WHERE id = ?", byte[].class, accountId);
        return stored != null && !java.util.Arrays.equals(stored, hasher.hash(hint.iban()));
    }
}
