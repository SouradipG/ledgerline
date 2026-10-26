package dev.souradip.ledgerline.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(@NotNull @Pattern(regexp = "USD|EUR|GBP|INR") String currency) {}
