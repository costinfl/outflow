package dev.costinfl.outflow.ingest.account;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "accounts")
public class AccountController {

    /** For statements without an IBAN: the user creates the account, then picks it on upload. */
    public record NewAccount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Account.Kind kind) {}

    /** Fields to change; absent fields stay as they are. */
    public record AccountChange(String name, Account.Kind kind) {}

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<Account> list() {
        return accounts.list();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Account create(@RequestBody NewAccount body) {
        if (body.name() == null || body.name().isBlank() || body.name().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be 1–100 characters");
        }
        if (body.currency() == null || !body.currency().matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an ISO code like RON");
        }
        if (body.kind() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        return accounts.create(body.name().strip(), body.currency(), body.kind());
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Account update(@PathVariable long id, @RequestBody AccountChange body) {
        String name = body.name() == null ? null : body.name().strip();
        if (name != null && (name.isEmpty() || name.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must be 1–100 characters");
        }
        return accounts.update(id, name, body.kind())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account " + id));
    }
}
