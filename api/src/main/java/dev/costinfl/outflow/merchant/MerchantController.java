package dev.costinfl.outflow.merchant;

import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.merchant.normalize.Alias;
import dev.costinfl.outflow.merchant.normalize.MerchantNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The raw → merchant key debug view (DESIGN: "Keep a debug view showing raw → key for the user to fix aliases"). */
@RestController
@RequestMapping(path = "/api/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "merchants")
public class MerchantController {

    public record MerchantSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long transactionCount,
            String categoryCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to 3 distinct raw descriptions, IBANs masked")
            List<String> sampleDescriptions) {}

    public record Explanation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MerchantNormalizer.Step> steps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
            String categoryCode,
            @Schema(description = "Which tier would categorize it: RULE, LEARNED or KEYWORD; absent when none")
            String categorySource) {}

    public record NewAlias(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Alias.MatchType matchType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Matched against the merchant key") String pattern,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String merchantKey) {}

    public record AliasResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int movedTransactions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int recategorizedTransactions) {}

    private final JdbcTemplate jdbc;
    private final MerchantService merchants;
    private final CategoryService categories;

    public MerchantController(JdbcTemplate jdbc, MerchantService merchants, CategoryService categories) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.categories = categories;
    }

    @GetMapping
    public List<MerchantSummary> list() {
        return jdbc.query("""
                SELECT m.id, m.key, m.display_name, count(t.id) AS n,
                       (SELECT c.code FROM transaction t2 JOIN category c ON c.id = t2.category_id
                        WHERE t2.merchant_id = m.id GROUP BY c.code ORDER BY count(*) DESC, c.code LIMIT 1) AS category_code,
                       (SELECT array_agg(d) FROM (SELECT DISTINCT description_raw AS d FROM transaction t3
                        WHERE t3.merchant_id = m.id ORDER BY 1 LIMIT 3) s) AS samples
                FROM merchant m JOIN transaction t ON t.merchant_id = m.id
                GROUP BY m.id ORDER BY n DESC, m.key""",
                (rs, i) -> new MerchantSummary(rs.getLong("id"), rs.getString("key"), rs.getString("display_name"),
                        rs.getLong("n"), rs.getString("category_code"),
                        Arrays.stream((String[]) rs.getArray("samples").getArray()).map(Iban::maskAll).toList()));
    }

    /** How a raw description becomes a merchant key, step by step, and which category tier would apply. */
    @GetMapping("/explain")
    public Explanation explain(@RequestParam String raw) {
        if (raw.isBlank() || raw.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "raw must be 1–500 characters");
        }
        var normalizer = merchants.normalizer();
        var steps = normalizer.trace(raw);
        String key = normalizer.key(raw);
        Long learned = jdbc.query("SELECT default_category_id FROM merchant WHERE key = ?",
                (rs, i) -> (Long) rs.getObject(1), key).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        var resolution = categories.resolver().resolve(key, learned);
        String code = resolution.map(r -> jdbc.queryForObject("SELECT code FROM category WHERE id = ?", String.class,
                r.categoryId())).orElse(null);
        return new Explanation(steps, key, code, resolution.map(r -> r.source().name()).orElse(null));
    }

    /** Adds (or replaces) a user alias, then recomputes merchants and categories for every transaction. */
    @PostMapping(path = "/aliases", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public AliasResult addAlias(@RequestBody NewAlias body) {
        Alias alias;
        try {
            alias = new Alias(body.matchType(), body.pattern() == null ? "" : body.pattern(),
                    body.merchantKey() == null ? "" : body.merchantKey());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "matchType, pattern and merchantKey are required");
        }
        jdbc.update("DELETE FROM merchant_alias WHERE match_type = ? AND pattern = ?", alias.matchType().name(), alias.pattern());
        jdbc.update("INSERT INTO merchant_alias (match_type, pattern, merchant_key, source) VALUES (?, ?, ?, 'USER')",
                alias.matchType().name(), alias.pattern(), alias.merchantKey());
        int moved = merchants.reassignAll();
        return new AliasResult(moved, categories.categorizeAll());
    }
}
