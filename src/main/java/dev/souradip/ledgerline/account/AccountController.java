package dev.souradip.ledgerline.account;

import dev.souradip.ledgerline.api.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return accounts.create(request.currency());
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable long id) {
        return accounts.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "account " + id + " does not exist"));
    }

    @GetMapping("/treasury/{currency}")
    public AccountView treasury(@PathVariable String currency) {
        return get(accounts.treasuryFor(currency.toUpperCase()));
    }
}
