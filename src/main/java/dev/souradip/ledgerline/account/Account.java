package dev.souradip.ledgerline.account;

public record Account(long id, String kind, String currency) {

    public boolean isTreasury() {
        return "TREASURY".equals(kind);
    }
}
