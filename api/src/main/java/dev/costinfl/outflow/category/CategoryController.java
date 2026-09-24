package dev.costinfl.outflow.category;

import dev.costinfl.outflow.txn.TransactionQueries;
import dev.costinfl.outflow.txn.TransactionView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "categories")
public class CategoryController {

    public record SetCategory(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long categoryId,
            @Schema(description = "Answer to 'apply to this merchant from now on?': creates a rule for the merchant")
            boolean applyToMerchant) {}

    public record CategoryChange(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransactionView transaction,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Transactions whose category changed, this one included")
            int changedTransactions) {}

    private final CategoryService categories;
    private final TransactionQueries transactions;

    public CategoryController(CategoryService categories, TransactionQueries transactions) {
        this.categories = categories;
        this.transactions = transactions;
    }

    @GetMapping(path = "/api/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Category> list() {
        return categories.list();
    }

    @PutMapping(path = "/api/transactions/{id}/category", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CategoryChange set(@PathVariable long id, @RequestBody SetCategory body) {
        requireTransaction(id);
        if (body.categoryId() == null || !categories.exists(body.categoryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category");
        }
        int changed = categories.setCategory(id, body.categoryId(), body.applyToMerchant());
        return new CategoryChange(requireTransaction(id), changed);
    }

    @DeleteMapping(path = "/api/transactions/{id}/category", produces = MediaType.APPLICATION_JSON_VALUE)
    public CategoryChange clear(@PathVariable long id) {
        requireTransaction(id);
        int changed = categories.clearManual(id);
        return new CategoryChange(requireTransaction(id), changed);
    }

    private TransactionView requireTransaction(long id) {
        return transactions.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No transaction " + id));
    }
}
