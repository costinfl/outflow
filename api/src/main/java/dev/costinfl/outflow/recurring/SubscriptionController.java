package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.merchant.MerchantService;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService.TransitionException;
import dev.costinfl.outflow.txn.Slice;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The Recurring payments screen and the user's answers about subscriptions (DESIGN: candidate lifecycle). */
@RestController
@RequestMapping(path = "/api/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "subscriptions")
public class SubscriptionController {

    @Schema(description = "Corrections made while confirming; omitted fields keep the detected value")
    public record Confirm(String name, Cadence cadence, Long expectedAmountMinor) {}

    @Schema(description = "Mark one transaction as a recurring payment (or recurring income)")
    public record AddManual(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long transactionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence cadence,
            @Schema(description = "Defaults to the merchant's name") String name) {}

    public record Merge(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The recurring payment it is the same as")
            Long into) {}

    public record Rename(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}

    @Schema(description = "Days before the next charge, 1–14; absent or null turns the reminder off")
    public record Reminder(Integer daysBefore) {}

    private final SubscriptionService subscriptions;
    private final RecurringService recurring;
    private final ReminderService reminders;
    private final MerchantService merchants;
    private final CategoryService categories;

    public SubscriptionController(SubscriptionService subscriptions, RecurringService recurring, ReminderService reminders,
            MerchantService merchants, CategoryService categories) {
        this.merchants = merchants;
        this.categories = categories;
        this.subscriptions = subscriptions;
        this.recurring = recurring;
        this.reminders = reminders;
    }

    /** "Remind me before next charge" on a confirmed recurring payment, or off. */
    @PutMapping(path = "/{id}/reminder", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Subscription reminder(@PathVariable long id, @RequestBody Reminder body) {
        try {
            reminders.set(id, body == null ? null : body.daysBefore());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ReminderService.ReminderException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return subscriptions.find(id).orElseThrow();
    }

    /** The reminders as a calendar file to import in the phone's calendar: it rings, even when Outflow is closed. */
    @GetMapping(path = "/reminders.ics", produces = "text/calendar")
    public org.springframework.http.ResponseEntity<String> calendar() {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"outflow-reminders.ics\"")
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .body(reminders.calendar());
    }

    /** The Recurring payments screen; with {@code month}, as it stood in that month (the home figure's drill-through). */
    @GetMapping
    public RecurringOverview overview(
            @Parameter(description = "YYYY-MM; absent = as of today") @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "RON") String currency,
            @Parameter(description = "Account ids to include (accounts filter); absent = all accounts")
            @RequestParam(required = false) List<Long> accounts) {
        Optional<YearMonth> ym;
        try {
            ym = Optional.ofNullable(month).map(YearMonth::parse);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        return recurring.overview(ym, new Slice(currency, accounts));
    }

    /**
     * "Mark as recurring" on one transaction (DESIGN: Manual add), e.g. a yearly renewal seen once: a confirmed
     * recurring payment from that charge. Charges already imported after it link at once.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Subscription addManual(@RequestBody AddManual body) {
        if (body.transactionId() == null || body.cadence() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transactionId and cadence are required");
        }
        if (body.name() != null && body.name().strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be at most 100 characters");
        }
        Subscription s;
        try {
            s = subscriptions.addManual(body.transactionId(), body.cadence(), body.name());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SubscriptionService.ManualException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        subscriptions.refreshNow();
        return subscriptions.find(s.id()).orElseThrow();
    }

    /** Rename a confirmed or ended subscription. */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Subscription rename(@PathVariable long id, @RequestBody Rename body) {
        if (body.name() == null || body.name().isBlank() || body.name().strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be 1–100 characters");
        }
        return answer(id, i -> subscriptions.rename(i, body.name()));
    }

    /** "Yes, it's a subscription" (optionally edited): PROPOSED or ENDED → CONFIRMED. */
    @PostMapping(path = "/{id}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Subscription confirm(@PathVariable long id, @RequestBody(required = false) Confirm body) {
        Confirm edits = body == null ? new Confirm(null, null, null) : body;
        if (edits.name() != null && edits.name().strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be at most 100 characters");
        }
        if (edits.expectedAmountMinor() != null && edits.expectedAmountMinor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedAmountMinor must be positive");
        }
        return answer(id, i -> subscriptions.confirm(i, new Edits(edits.name(), edits.cadence(), edits.expectedAmountMinor())));
    }

    /**
     * "Same as…" on a proposal: it is {@code into} under another merchant name (DESIGN: merge, "same service, two merchant
     * keys"). Its merchant becomes {@code into}'s everywhere; merchants, categories and subscriptions are recomputed and
     * the proposal's charges join {@code into}. Returns {@code into}.
     */
    @PostMapping(path = "/{id}/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Subscription merge(@PathVariable long id, @RequestBody Merge body) {
        if (body.into() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "into is required");
        }
        try {
            subscriptions.mergeInto(id, body.into());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (TransitionException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (SubscriptionService.MergeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        merchants.reassignAll();
        categories.categorizeAll();
        subscriptions.refreshNow();
        return subscriptions.find(body.into()).orElseThrow();
    }

    /** "Not recurring": PROPOSED → REJECTED, never proposed again. */
    @PostMapping("/{id}/reject")
    public Subscription reject(@PathVariable long id) {
        return answer(id, subscriptions::reject);
    }

    /** "Mark ended": CONFIRMED → ENDED. */
    @PostMapping("/{id}/end")
    public Subscription end(@PathVariable long id) {
        return answer(id, subscriptions::end);
    }

    private Subscription answer(long id, LongFunction<Subscription> transition) {
        try {
            return transition.apply(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription " + id);
        } catch (TransitionException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
