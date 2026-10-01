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
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03", description = "The period's last month")
            String month,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1, or 3 for the 3 months ending with `month`")
            int months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Scope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "SPEND: money spent (positive); INCOME: money in; ALL: net (in − out)")
            long totalMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Newest first") List<TransactionView> items) {}

    private final TransactionQueries transactions;
    private final Currencies currencies;

    public TransactionController(TransactionQueries transactions, Currencies currencies) {
        this.transactions = transactions;
        this.currencies = currencies;
    }

    @GetMapping
    public TransactionList list(
            @Parameter(description = "YYYY-MM") @RequestParam String month,
            @Parameter(description = "ISO currency; absent = the main currency of the accounts (most transactions)") @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "ALL") Scope scope,
            @RequestParam(required = false) Long category,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) Long merchant,
            @Parameter(description = "Merchant or bank text contains it, or the amount equals it") @RequestParam(required = false) String q,
            @Parameter(description = "1, or 3 for the 'last 3 months' view ending with the month")
            @RequestParam(defaultValue = "1") int months,
            @Parameter(description = "Account ids to include (accounts filter); absent = all accounts")
            @RequestParam(required = false) List<Long> accounts,
            @Parameter(description = "true: only charges of confirmed or ended recurring payments; false: only the rest")
            @RequestParam(required = false) Boolean recurring,
            @Parameter(description = "Only the charges of this recurring payment")
            @RequestParam(required = false) Long subscription) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        Slice slice;
        try {
            slice = currencies.slice(currency, accounts);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (months != 1 && months != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be 1 or 3");
        }
        var f = TransactionFilter.month(ym, slice).lastMonths(months).matching(q);
        f = switch (scope) {
            case SPEND -> f.spend();
            case INCOME -> f.income();
            case ALL -> f;
        };
        if (category != null) f = f.inCategory(category);
        if (uncategorized) f = f.uncategorizedOnly();
        if (merchant != null) f = f.atMerchant(merchant);
        if (recurring != null) f = f.recurringOnly(recurring);
        if (subscription != null) f = f.ofSubscription(subscription);
        var items = transactions.list(f);
        long sum = items.stream().mapToLong(TransactionView::amountMinor).sum();
        return new TransactionList(ym.toString(), months, slice.currency(), scope, scope == Scope.SPEND ? -sum : sum, items.size(), items);
    }
}
