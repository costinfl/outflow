package dev.costinfl.outflow.split;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** "Splits to settle" (CP6.22): who owes you what for shared expenses, and the money that paid it back. */
@RestController
@Tag(name = "splits")
public class SettlementController {

    public record RepaymentSuggestion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long transactionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate bookingDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Who it came from, as the bank names it")
            String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Not yet linked to other repayments")
            long availableMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Its amount is what is open") boolean sameAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Its bank text names the person")
            boolean namesPerson) {}

    public record SplitRepayment(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(description = "The money in that paid it; absent when paid outside the bank") Long transactionId,
            LocalDate bookingDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor) {}

    public record SplitDebt(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Use it to settle") long splitPersonId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The shared expense") long transactionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate bookingDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String merchantName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long owedMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long settledMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long openMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SplitRepayment> repayments,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Best first; only while something is open")
            List<RepaymentSuggestion> suggestions) {}

    public record SplitBalance(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What they still owe") long openMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Their parts of all splits") long owedMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Open first, newest first") List<SplitDebt> debts) {}

    public record SplitBalances(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Who owes the most first")
            List<SplitBalance> people) {}

    public record SettleInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long splitPersonId,
            @Schema(description = "The money in that paid it; absent = paid outside the bank (cash, another account)")
            Long transactionId,
            @Schema(description = "Absent = as much as is open (and still unlinked in that money in)") Long amountMinor) {}

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping(path = "/api/splits/balances", produces = MediaType.APPLICATION_JSON_VALUE)
    public SplitBalances balances() {
        return new SplitBalances(settlements.balances().stream().map(p -> new SplitBalance(p.name(), p.currency(),
                p.openMinor(), p.owedMinor(), p.items().stream().map(i -> new SplitDebt(i.splitPersonId(),
                        i.transactionId(), i.bookingDate(), i.merchantName(), i.owedMinor(), i.settledMinor(), i.openMinor(),
                        i.settlements().stream().map(s -> new SplitRepayment(s.id(), s.transactionId(), s.bookingDate(),
                                s.amountMinor())).toList(),
                        i.suggestions().stream().map(s -> new RepaymentSuggestion(s.transactionId(), s.bookingDate(),
                                s.name(), s.amountMinor(), s.availableMinor(), s.sameAmount(), s.namesPerson())).toList()))
                        .toList())).toList());
    }

    /** Records a repayment; 409 when already settled or the money cannot pay back, 400 for an amount that does not fit. */
    @PostMapping(path = "/api/splits/settlements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SplitBalances> settle(@RequestBody SettleInput input) {
        try {
            settlements.settle(input.splitPersonId(), input.transactionId(), input.amountMinor());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (SettlementService.SettlementException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(balances());
    }

    @DeleteMapping("/api/splits/settlements/{id}")
    public ResponseEntity<Void> unsettle(@PathVariable long id) {
        if (!settlements.unsettle(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No repayment " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
