package dev.costinfl.outflow.txn;

import dev.costinfl.outflow.ingest.UploadService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The user's approximate exchange rates, used only to recognise transfers between own accounts in two currencies
 * (CP6.19). Setting or removing one reruns the derived stages, so pairs form or dissolve at once.
 */
@RestController
@RequestMapping(path = "/api/fx-rates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "transactions")
public class FxRateController {

    /** One currency pair: 1 {@code base} = {@code rate} {@code quote}. */
    public record FxPair(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR") String base,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String quote,
            @Schema(description = "The user's rate: 1 base = rate quote; absent when not set (then only an IBAN pairs "
                    + "transfers between the two currencies)", example = "4.97") BigDecimal rate,
            @Schema(description = "How far a transfer's amounts may be from the rate, in percent; absent when not set")
            BigDecimal tolerancePercent,
            @Schema(description = "The rate of the latest paired transfer between the two currencies, as a hint")
            BigDecimal lastSeenRate,
            @Schema(description = "The date of that transfer") LocalDate lastSeenOn) {}

    public record FxRateInput(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 base = rate quote", example = "4.97")
            BigDecimal rate,
            @Schema(description = "0–10 percent; default 3") BigDecimal tolerancePercent) {}

    private final FxRates rates;
    private final UploadService pipeline;

    public FxRateController(FxRates rates, UploadService pipeline) {
        this.rates = rates;
        this.pipeline = pipeline;
    }

    /** Every pair of the currencies the money is in (alphabetical), and any pair with a rate. */
    @GetMapping
    public List<FxPair> list() {
        return rates.overview().stream().map(p -> new FxPair(p.base(), p.quote(),
                p.rate() == null ? null : p.rate().rate(), p.rate() == null ? null : p.rate().tolerancePercent(),
                p.lastSeenRate(), p.lastSeenOn())).toList();
    }

    /** Sets 1 {@code base} = {@code rate} {@code quote} (either order; stored alphabetically) and re-pairs transfers. */
    @PutMapping(path = "/{base}/{quote}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public List<FxPair> set(@PathVariable String base, @PathVariable String quote, @RequestBody FxRateInput input) {
        try {
            rates.set(base, quote, input.rate(), input.tolerancePercent());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        pipeline.derive();
        return list();
    }

    /** Removes the pair's rate: transfers between the two currencies then pair only by IBAN. */
    @DeleteMapping("/{base}/{quote}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String base, @PathVariable String quote) {
        boolean removed;
        try {
            removed = rates.delete(base, quote);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No rate for " + base + "/" + quote);
        }
        pipeline.derive();
        return ResponseEntity.noContent().build();
    }
}
