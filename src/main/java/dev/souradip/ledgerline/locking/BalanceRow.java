package dev.souradip.ledgerline.locking;

record BalanceRow(long accountId, long balanceMinor, boolean allowNegative, long version) {

    boolean canDebit(long amountMinor) {
        return allowNegative || balanceMinor >= amountMinor;
    }
}
