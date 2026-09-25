package dev.costinfl.outflow.txn;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "transactions")
public class TransactionController {

    public enum Scope { SPEND, INCOME, ALL }

    /** The transactions behind a number, and their total in the same sense as the number (spent is positive). */
    public record TransactionList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03") String month,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Scope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "SPEND: money spent (positive); INCOME: money in; ALL: net (in − out)")
            long totalMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Newest first") List<TransactionView> items) {}

    private final TransactionQueries transactions;

    public TransactionController(TransactionQueries transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    public TransactionList list(
            @Parameter(description = "YYYY-MM") @RequestParam String month,
            @RequestParam(defaultValue = "RON") String currency,
            @RequestParam(defaultValue = "ALL") Scope scope,
            @RequestParam(required = false) Long category,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) Long merchant,
            @Parameter(description = "Merchant or bank text contains it, or the amount equals it") @RequestParam(required = false) String q,
            @Parameter(description = "Account ids to include (accounts filter); absent = all accounts")
            @RequestParam(required = false) List<Long> accounts) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        var f = TransactionFilter.month(ym, new Slice(currency, accounts)).matching(q);
        f = switch (scope) {
            case SPEND -> f.spend();
            case INCOME -> f.income();
            case ALL -> f;
        };
        if (category != null) f = f.inCategory(category);
        if (uncategorized) f = f.uncategorizedOnly();
        if (merchant != null) f = f.atMerchant(merchant);
        var items = transactions.list(f);
        long sum = items.stream().mapToLong(TransactionView::amountMinor).sum();
        return new TransactionList(ym.toString(), currency, scope, scope == Scope.SPEND ? -sum : sum, items.size(), items);
    }
}
