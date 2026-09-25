package dev.costinfl.outflow.review;

import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.AlertService;
import dev.costinfl.outflow.txn.SoftMatchService;
import java.util.NoSuchElementException;
import org.springframework.transaction.annotation.Transactional;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The review inbox and the answers that are not subscription transitions (DESIGN: Review inbox). */
@RestController
@RequestMapping(path = "/api/review", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "review")
public class ReviewController {

    public record Skip(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key) {}

    public record MerchantCategory(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long categoryId) {}

    public record Duplicate(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "true: the same payment, pending then posted")
            Boolean same) {}

    public record AlertAnswer(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Price change: GOT_IT or END. Missed charge: STILL_ACTIVE or CANCELLED")
            AlertService.Action action) {}

    public record MerchantCategoryResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Transactions whose category changed")
            int changedTransactions) {}

    private final ReviewService review;
    private final SoftMatchService softMatches;
    private final AlertService alerts;
    private final UploadService pipeline;
    private final CategoryService categories;
    private final SubscriptionService subscriptions;
    private final JdbcTemplate jdbc;

    public ReviewController(ReviewService review, CategoryService categories, SubscriptionService subscriptions,
            JdbcTemplate jdbc, SoftMatchService softMatches, UploadService pipeline, AlertService alerts) {
        this.alerts = alerts;
        this.review = review;
        this.softMatches = softMatches;
        this.pipeline = pipeline;
        this.categories = categories;
        this.subscriptions = subscriptions;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Inbox inbox() {
        return review.inbox();
    }

    /** Skipped cards come back after the next upload. */
    @PostMapping(path = "/skip", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@RequestBody Skip body) {
        if (body.key() == null || body.key().isBlank() || body.key().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key is required");
        }
        review.skip(body.key());
    }

    /**
     * "Same · Different" on a possible duplicate. Same: the pending row is replaced by the posted one for good.
     * Different: these two are never matched again. Transfers and subscriptions follow.
     */
    @PostMapping(path = "/duplicates/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void answerDuplicate(@PathVariable long id, @RequestBody Duplicate body) {
        if (body.same() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "same is required");
        }
        try {
            softMatches.answer(id, body.same());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No question " + id);
        } catch (SoftMatchService.AnsweredException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        pipeline.derive();
    }

    /** "Got it · Mark ended" on a price change, "Cancelled · Still active" on a missed charge. */
    @PostMapping(path = "/alerts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answerAlert(@PathVariable long id, @RequestBody AlertAnswer body) {
        if (body.action() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        }
        try {
            alerts.answer(id, body.action());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No alert " + id);
        } catch (AlertService.AnswerException e) {
            throw new ResponseStatusException(e.conflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** "Pick category (applies to all)": a rule for the merchant; every automatic transaction of it follows. */
    @PostMapping(path = "/merchants/{merchantId}/category", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MerchantCategoryResult categorizeMerchant(@PathVariable long merchantId, @RequestBody MerchantCategory body) {
        if (jdbc.queryForObject("SELECT count(*) FROM merchant WHERE id = ?", Long.class, merchantId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No merchant " + merchantId);
        }
        if (body.categoryId() == null || !categories.exists(body.categoryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category");
        }
        int changed = categories.setMerchantCategory(merchantId, body.categoryId());
        subscriptions.refreshNow(); // a merchant marked as transfer or cash is no longer a subscription candidate
        return new MerchantCategoryResult(changed);
    }
}
