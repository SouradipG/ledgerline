package dev.souradip.ledgerline.account;

public record AccountView(long id, String kind, String currency, long balanceMinor, long version) {}
