package dev.costinfl.outflow.split;

import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.split.SettlementController.RepaymentSuggestion;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** "They paid, I owe" (CP6.23): what the user owes people who paid for something shared, and the money that paid it. */
@RestController
@Tag(name = "splits")
public class IouController {

    public record IouPaymentView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(description = "The money out that paid it; absent when paid outside the bank") Long transactionId,
            LocalDate bookingDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor) {}

    public record IouView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String person,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate incurredOn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What you owe") long amountMinor,
            Long categoryId,
            String categoryName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long paidMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long openMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IouPaymentView> payments,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Money out that may have paid it; best first")
            List<RepaymentSuggestion> suggestions) {}

    public record IouCreditor(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What you still owe them") long openMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long owedMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Open first, newest first") List<IouView> ious) {}

    public record IouList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whom you owe the most first")
            List<IouCreditor> people) {}

    public record IouInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Ana") String person,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Dinner at La Mama") String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate incurredOn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Your share, minor units") long amountMinor,
            @Schema(description = "What it was; the payment that settles it takes this category") Long categoryId) {}

    public record IouPayInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long iouId,
            @Schema(description = "The money out that paid it; absent = paid outside the bank") Long transactionId,
            @Schema(description = "Absent = as much as is open (and still unlinked in that money out)") Long amountMinor) {}

    private final IouService ious;
    private final UploadService pipeline;

    public IouController(IouService ious, UploadService pipeline) {
        this.ious = ious;
        this.pipeline = pipeline;
    }

    @GetMapping(path = "/api/ious", produces = MediaType.APPLICATION_JSON_VALUE)
    public IouList list() {
        return new IouList(ious.list().stream().map(c -> new IouCreditor(c.name(), c.currency(), c.openMinor(),
                c.owedMinor(), c.ious().stream().map(i -> new IouView(i.id(), i.person(), i.description(), i.incurredOn(),
                        i.currency(), i.amountMinor(), i.categoryId(), i.categoryName(), i.paidMinor(), i.openMinor(),
                        i.payments().stream().map(p -> new IouPaymentView(p.id(), p.transactionId(), p.bookingDate(),
                                p.amountMinor())).toList(),
                        i.suggestions().stream().map(s -> new RepaymentSuggestion(s.transactionId(), s.bookingDate(),
                                s.name(), s.amountMinor(), s.availableMinor(), s.sameAmount(), s.namesPerson())).toList()))
                        .toList())).toList());
    }

    @PostMapping(path = "/api/ious", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IouList> add(@RequestBody IouInput input) {
        try {
            ious.add(input.person(), input.description(), input.incurredOn(), input.currency(), input.amountMinor(),
                    input.categoryId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(list());
    }

    @DeleteMapping("/api/ious/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        boolean removed;
        try {
            removed = ious.delete(id);
        } catch (IouService.IouException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No IOU " + id);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Records a payment; a linked one takes the IOU's category (the derived stages rerun, so recurring payments see it).
     * 409 when already paid or the money cannot pay it, 400 for an amount that does not fit.
     */
    @PostMapping(path = "/api/ious/payments", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<IouList> pay(@RequestBody IouPayInput input) {
        try {
            ious.pay(input.iouId(), input.transactionId(), input.amountMinor());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IouService.IouException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        pipeline.derive();
        return ResponseEntity.status(HttpStatus.CREATED).body(list());
    }

    @DeleteMapping("/api/ious/payments/{id}")
    public ResponseEntity<Void> unpay(@PathVariable long id) {
        if (!ious.unpay(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
