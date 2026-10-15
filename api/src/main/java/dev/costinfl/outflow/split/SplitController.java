package dev.costinfl.outflow.split;

import dev.costinfl.outflow.split.SplitCalculator.Mode;
import dev.costinfl.outflow.split.SplitCalculator.PersonInput;
import dev.costinfl.outflow.split.SplitService.SplitView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Shared expenses (CP6.21): split a payment with other people; figures then count only your share. */
@RestController
@Tag(name = "splits")
public class SplitController {

    public record ExpenseSplitPersonInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Ana") String name,
            @Schema(description = "PERCENT mode: above 0, at most 100", example = "50") BigDecimal percent,
            @Schema(description = "FIXED mode: minor units", example = "12000") Long fixedMinor,
            @Schema(description = "SHARES mode: a weight from 1", example = "1") Integer shares) {}

    public record ExpenseSplitInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Mode mode,
            @Schema(description = "SHARES mode: your own weight, 0 if you only paid; default 1") Integer myShares,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ExpenseSplitPersonInput> people) {}

    public record ExpenseSplitPerson(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            BigDecimal percent,
            Long fixedMinor,
            Integer shares,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What they owe, minor units") long amountMinor) {}

    public record ExpenseSplit(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long transactionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.time.LocalDate bookingDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String merchantName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The whole expense, positive minor units")
            long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Mode mode,
            Integer myShares,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Your share: what figures count")
            long mineMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What the others owe together")
            long othersMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ExpenseSplitPerson> people) {}

    public record ExpenseSplitList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Newest expense first") List<ExpenseSplit> splits,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Names used so far, most used first")
            List<String> people) {}

    private final SplitService splits;

    public SplitController(SplitService splits) {
        this.splits = splits;
    }

    @GetMapping(path = "/api/splits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExpenseSplitList list() {
        return new ExpenseSplitList(splits.list().stream().map(SplitController::view).toList(), splits.people());
    }

    @GetMapping(path = "/api/transactions/{id}/split", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExpenseSplit get(@PathVariable long id) {
        return splits.find(id).map(SplitController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction " + id + " is not split"));
    }

    /** Splits a payment (or changes its split); 409 for money in or a pending row, 400 when it does not add up. */
    @PutMapping(path = "/api/transactions/{id}/split", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExpenseSplit put(@PathVariable long id, @RequestBody ExpenseSplitInput input) {
        try {
            var people = input.people() == null ? List.<PersonInput>of() : input.people().stream()
                    .map(p -> new PersonInput(p.name(), p.percent(), p.fixedMinor(), p.shares())).toList();
            return view(splits.save(id, input.mode(), input.myShares(), people));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SplitService.NotSplittableException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/api/transactions/{id}/split")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!splits.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction " + id + " is not split");
        }
        return ResponseEntity.noContent().build();
    }

    static ExpenseSplit view(SplitView v) {
        return new ExpenseSplit(v.transactionId(), v.bookingDate(), v.merchantName(), v.currency(), v.amountMinor(), v.mode(),
                v.myShares(), v.mineMinor(), v.othersMinor(), v.people().stream()
                        .map(p -> new ExpenseSplitPerson(p.name(), p.percent(), p.fixedMinor(), p.shares(), p.amountMinor()))
                        .toList());
    }
}
