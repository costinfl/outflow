package dev.costinfl.outflow.ingest;

import dev.costinfl.outflow.ingest.FileOutcome.Status;
import dev.costinfl.outflow.ingest.ImportSummary.AccountImport;
import dev.costinfl.outflow.ingest.account.AccountService;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "imports")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
    static final int MAX_FILES = 20;

    private final UploadService uploads;
    private final AccountService accounts;

    public ImportController(UploadService uploads, AccountService accounts) {
        this.uploads = uploads;
        this.accounts = accounts;
    }

    /**
     * Imports several statement files. Each file is its own DB transaction: one bad file never blocks or partly
     * writes the others. Any bank, any overlap: rows already imported are skipped and counted.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummary importFiles(
            @RequestPart("files") List<MultipartFile> files,
            @Parameter(description = "Account for files that do not name one (no IBAN)")
            @RequestParam(required = false) Long accountId,
            @Parameter(description = "Force a parser instead of detection, e.g. after NEEDS_PARSER")
            @RequestParam(required = false) String parserId) {
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload 1–" + MAX_FILES + " files");
        }
        if (accountId != null && accounts.find(accountId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown account " + accountId);
        }

        var results = new ArrayList<UploadService.Upload>();
        for (MultipartFile file : files) {
            String name = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
            try {
                results.add(uploads.importOne(name, file.getBytes(), Optional.ofNullable(accountId),
                        Optional.ofNullable(parserId)));
            } catch (StatementParseException e) {
                results.add(UploadService.Upload.skipped(
                        FileOutcome.notImported(name, Status.FAILED, e.getMessage(), List.of())));
            } catch (IOException | RuntimeException e) {
                // Unexpected: log the type only (a message could echo file content), tell the user generically.
                log.error("Import of an uploaded file failed: {}", e.getClass().getName(), e);
                results.add(UploadService.Upload.skipped(FileOutcome.notImported(name, Status.FAILED,
                        "Unexpected error while importing this file; nothing from it was stored", List.of())));
            }
        }
        return summarize(results);
    }

    static ImportSummary summarize(List<UploadService.Upload> results) {
        var byAccount = new LinkedHashMap<Long, AccountImport>();
        for (var r : results) {
            if (r.account().isEmpty()) {
                continue;
            }
            var o = r.outcome();
            var account = r.account().get();
            byAccount.merge(account.id(),
                    new AccountImport(account, r.accountCreated(), o.newTransactions(), o.alreadyImported(),
                            o.periodFrom(), o.periodTo()),
                    (a, b) -> new AccountImport(account, a.created() || b.created(),
                            a.newTransactions() + b.newTransactions(), a.alreadyImported() + b.alreadyImported(),
                            min(a.periodFrom(), b.periodFrom()), max(a.periodTo(), b.periodTo())));
        }
        var files = results.stream().map(UploadService.Upload::outcome).toList();
        return new ImportSummary(files, List.copyOf(byAccount.values()),
                files.stream().mapToInt(FileOutcome::newTransactions).sum(),
                files.stream().mapToInt(FileOutcome::alreadyImported).sum(),
                files.stream().mapToInt(FileOutcome::transfers).sum());
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return Stream.of(a, b).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return Stream.of(a, b).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
