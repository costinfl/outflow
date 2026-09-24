package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService.TransitionException;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.NoSuchElementException;
import java.util.function.LongFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The user's answers to subscription questions (DESIGN: Subscription candidate lifecycle). */
@RestController
@RequestMapping(path = "/api/subscriptions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "subscriptions")
public class SubscriptionController {

    @Schema(description = "Corrections made while confirming; omitted fields keep the detected value")
    public record Confirm(String name, Cadence cadence, Long expectedAmountMinor) {}

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** "Yes, it's a subscription" (optionally edited): PROPOSED or ENDED → CONFIRMED. */
    @PostMapping(path = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
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
    @PostMapping("/reject")
    public Subscription reject(@PathVariable long id) {
        return answer(id, subscriptions::reject);
    }

    /** "Mark ended": CONFIRMED → ENDED. */
    @PostMapping("/end")
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
