package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService.TransitionException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The Recurring payments screen and the user's answers about subscriptions (DESIGN: candidate lifecycle). */
@RestController
@RequestMapping(path = "/api/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "subscriptions")
public class SubscriptionController {

    @Schema(description = "Corrections made while confirming; omitted fields keep the detected value")
    public record Confirm(String name, Cadence cadence, Long expectedAmountMinor) {}

    public record Rename(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}

    private final SubscriptionService subscriptions;
    private final RecurringService recurring;

    public SubscriptionController(SubscriptionService subscriptions, RecurringService recurring) {
        this.subscriptions = subscriptions;
        this.recurring = recurring;
    }

    /** The Recurring payments screen; with {@code month}, as it stood in that month (the home figure's drill-through). */
    @GetMapping
    public RecurringOverview overview(
            @Parameter(description = "YYYY-MM; absent = as of today") @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "RON") String currency) {
        Optional<YearMonth> ym;
        try {
            ym = Optional.ofNullable(month).map(YearMonth::parse);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        return recurring.overview(ym, currency);
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
