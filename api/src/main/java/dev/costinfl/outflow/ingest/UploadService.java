package dev.costinfl.outflow.ingest;

import dev.costinfl.outflow.ingest.FileOutcome.ParserCandidate;
import dev.costinfl.outflow.ingest.FileOutcome.Status;
import dev.costinfl.outflow.ingest.account.Account;
import dev.costinfl.outflow.ingest.account.AccountService;
import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementDetector;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.merchant.MerchantService;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.txn.TransferService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One uploaded file, end to end, in one DB transaction: pick parser → parse → resolve account → import → merchants →
 * categories → transfers → subscriptions.
 */
@Service
public class UploadService {

    /** A file's outcome plus the account it went to, for the per-account summary. */
    public record Upload(FileOutcome outcome, Optional<Account> account, boolean accountCreated) {

        static Upload skipped(FileOutcome outcome) {
            return new Upload(outcome, Optional.empty(), false);
        }
    }

    private final StatementDetector detector;
    private final AccountService accounts;
    private final ImportService imports;
    private final MerchantService merchants;
    private final CategoryService categories;
    private final TransferService transfers;
    private final SubscriptionService subscriptions;

    public UploadService(StatementDetector detector, AccountService accounts, ImportService imports,
            MerchantService merchants, CategoryService categories, TransferService transfers,
            SubscriptionService subscriptions) {
        this.detector = detector;
        this.accounts = accounts;
        this.imports = imports;
        this.merchants = merchants;
        this.categories = categories;
        this.transfers = transfers;
        this.subscriptions = subscriptions;
    }

    /**
     * @throws StatementParseException when the file cannot be read; the transaction rolls back and nothing is stored
     */
    @Transactional
    public Upload importOne(String fileName, byte[] content, Optional<Long> accountId, Optional<String> parserId)
            throws IOException {
        var detection = detector.detect(FileSample.of(fileName, content));
        var candidates = detection.candidates().stream()
                .map(c -> new ParserCandidate(c.parser().id(), c.parser().displayName(), c.score().value(), c.score().reason()))
                .toList();

        Optional<StatementParser> parser = parserId.isPresent() ? detector.byId(parserId.get()) : detection.chosen();
        if (parser.isEmpty()) {
            return parserId.isPresent()
                    ? Upload.skipped(FileOutcome.notImported(fileName, Status.FAILED,
                            "Unknown parser '" + parserId.get() + "'", candidates))
                    : Upload.skipped(FileOutcome.notImported(fileName, Status.NEEDS_PARSER,
                            "Could not tell which bank format this is; pick one of the candidates", candidates));
        }

        ParsedStatement parsed = parser.get().parse(new ByteArrayInputStream(content));

        Account account;
        boolean created = false;
        if (parsed.accountHint().isPresent()) {
            var hint = parsed.accountHint().get();
            if (accountId.isPresent() && accounts.contradicts(accountId.get(), hint)) {
                return Upload.skipped(FileOutcome.notImported(fileName, Status.FAILED,
                        "This file belongs to account " + hint.iban().masked() + ", not the one you picked", List.of()));
            }
            if (accountId.isPresent()) {
                account = accounts.find(accountId.get()).orElseThrow();
            } else {
                var resolved = accounts.resolve(hint, parsed.rows().stream().map(ParsedRow::currency).findFirst().orElse("RON"));
                account = resolved.account();
                created = resolved.created();
            }
        } else if (accountId.isPresent()) {
            account = accounts.find(accountId.get()).orElseThrow();
        } else {
            return Upload.skipped(FileOutcome.notImported(fileName, Status.NEEDS_ACCOUNT,
                    "This file does not say which account it is from; pick the account", List.of()));
        }

        ImportResult r = imports.importParsed(account.id(), fileName, content, parser.get().id(), parsed);
        // Stages G–I in the same DB transaction: new transactions get their merchant and category, own-account
        // transfers are paired (they override the category with TRANSFER), then the recurrence detector sees them.
        merchants.assignMissing();
        categories.categorizeAll();
        var transferResult = transfers.pairAll();
        subscriptions.refreshNow();
        var outcome = new FileOutcome(fileName, r.duplicateFile() ? Status.DUPLICATE_FILE : Status.IMPORTED, null,
                r.parserId(), account.id(), r.rows(), r.newTransactions(), r.alreadyImported(),
                r.periodFrom().orElse(null), r.periodTo().orElse(null), transferResult.transactions(), List.of());
        return new Upload(outcome, Optional.of(account), created);
    }
}
