package compression.kangaroo;

import java.util.Arrays;

/** Window of transformed distinct-run values, partitioned by trailing zeros. */
final class History {
    private final long[] values, ids, previousSame;
    private final int[] edgeLead;
    private final long[] head = new long[65];
    private final long[][] shortcut;
    private final int mask;
    private final Options.Search search;
    private long next;

    History(int window, Options.Search search) {
        values = new long[window]; ids = new long[window]; previousSame = new long[window]; edgeLead = new int[window];
        mask = window - 1; this.search = search;
        Arrays.fill(ids, -1); Arrays.fill(head, -1);
        shortcut = search == Options.Search.FAST ? new long[65][64] : null;
        if (shortcut != null) for (long[] row : shortcut) Arrays.fill(row, -1);
    }
    boolean empty() { return next == 0; }
    int slot(long id) { return (int) id & mask; }
    boolean live(long id) { return id >= 0 && id < next && id >= next - values.length && ids[slot(id)] == id; }
    long value(long id) { if (!live(id)) throw new IllegalArgumentException("Expired reference"); return values[slot(id)]; }
    long fromSlot(int slot) { return value(ids[slot]); }
    long latest() { return next - 1; }

    long select(long current) {
        long newest = head[Long.numberOfTrailingZeros(current)];
        if (!live(newest)) return -1;
        int maxLead = Long.numberOfLeadingZeros(current ^ value(newest));
        if (maxLead == 64) return newest;
        if (search == Options.Search.FAST) {
            long candidate = shortcut[Long.numberOfTrailingZeros(current)][maxLead];
            // Theorem 2: at key k, the first eligible edge has lead=k,
            // so its left endpoint necessarily agrees with current past k.
            // Maintenance establishes this invariant; no second XOR is needed.
            return live(candidate) ? candidate : newest;
        }
        long best = newest, right = newest;
        int minLead = maxLead;
        while (live(previousSame[slot(right)])) {
            long left = previousSame[slot(right)];
            int ell = edgeLead[slot(right)];
            if (ell < minLead) minLead = ell;
            else if (ell == minLead) {
                minLead = Long.numberOfLeadingZeros(current ^ value(left));
                if (minLead > maxLead) { maxLead = minLead; best = left; }
            }
            right = left;
        }
        return best;
    }

    void add(long current) {
        int t = Long.numberOfTrailingZeros(current);
        long oldHead = head[t];
        boolean oldLive = live(oldHead);
        int ell = oldLive ? Long.numberOfLeadingZeros(value(oldHead) ^ current) : 64;
        if (search == Options.Search.FAST) {
            // First-hop interpretation of section 4 / Theorem 2 (see ALIGNMENT.md).
            // Prefixes shorter than ell survive. At ell the old head becomes
            // the opposite-branch candidate; longer prefixes are invalidated.
            if (!oldLive) Arrays.fill(shortcut[t], -1);
            else if (ell < 64) {
                shortcut[t][ell] = oldHead;
                Arrays.fill(shortcut[t], ell + 1, 64, -1);
            }
        }
        int slot = slot(next);
        values[slot] = current; ids[slot] = next;
        previousSame[slot] = oldLive ? oldHead : -1;
        edgeLead[slot] = ell;
        head[t] = next++;
    }
}
