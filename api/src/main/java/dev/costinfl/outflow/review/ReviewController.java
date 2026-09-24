package dev.costinfl.outflow.review;

import dev.costinfl.outflow.category.CategoryService;
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

    public record MerchantCategoryResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Transactions whose category changed")
            int changedTransactions) {}

    private final ReviewService review;
    private final CategoryService categories;
    private final SubscriptionService subscriptions;
    private final JdbcTemplate jdbc;

    public ReviewController(ReviewService review, CategoryService categories, SubscriptionService subscriptions,
            JdbcTemplate jdbc) {
        this.review = review;
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
