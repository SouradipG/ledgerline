package dev.souradip.ledgerline.locking;

final class BalanceOrder {

    private BalanceOrder() {}

    static long[] ascending(long a, long b) {
        return a < b ? new long[] {a, b} : new long[] {b, a};
    }
}
