package compression.kangaroo;

import java.nio.ByteBuffer;

/** Incremental encoder producing one independently decodable block on finish(). */
public final class KangarooEncoder {
    static final int MAGIC = 0x4b47524a; // KGRJ: independent format, NOT author-compatible.
    static final int HEADER_BYTES = 20;
    static final int[] LEADING = {0, 8, 12, 16, 18, 20, 22, 24};
    private final Options options;
    private final History history;
    private final BitStream.Writer out = new BitStream.Writer();
    private int count, pending;
    private long lastOriginal;
    private byte[] finished;
    private final Statistics statistics = new Statistics();

    public static final class Statistics {
        public long unchanged, erased, flipped, rejectedErasure, repeated;
        public long sameReference, sharedTrailing, fallback, payloadBits;
        private Statistics copy() {
            Statistics s = new Statistics();
            s.unchanged=unchanged; s.erased=erased; s.flipped=flipped; s.rejectedErasure=rejectedErasure;
            s.repeated=repeated; s.sameReference=sameReference; s.sharedTrailing=sharedTrailing; s.fallback=fallback; s.payloadBits=payloadBits;
            return s;
        }
    }

    public KangarooEncoder() { this(Options.defaults()); }
    public KangarooEncoder(Options options) { this.options=options; history=new History(options.window, options.search); }
    public void add(double value) { addRaw(Double.doubleToRawLongBits(value)); }

    /** Also accepts NaN payloads without converting them through floating-point arithmetic. */
    public void addRaw(long original) {
        if (finished != null) throw new IllegalStateException("Block is finished");
        if (count == Integer.MAX_VALUE) throw new IllegalStateException("Block has too many records");
        if (options.rle && count > 0 && original == lastOriginal) {
            count++; pending++; statistics.repeated++; return;
        }
        writeRepeats();
        count++;
        Erasure.Result transformed = Erasure.encode(original, options.erasure);
        if (transformed.kind == Erasure.UNCHANGED) { out.write(7,3); statistics.unchanged++; }
        else if (transformed.kind == Erasure.ERASED) { out.write(0,1); statistics.erased++; }
        else { out.write(2,2); statistics.flipped++; }
        if (transformed.rejected) statistics.rejectedErasure++;
        writeValue(transformed.stored);
        history.add(transformed.stored);
        lastOriginal=original;
    }

    private void writeRepeats() {
        if (pending == 0) return;
        out.write(6,3); // 110, followed by unsigned LEB128 additional repetition count.
        int n=pending;
        do {
            int b=n & 127; n >>>=7;
            out.write(b | (n == 0 ? 0 : 128),8);
        } while (n != 0);
        pending=0;
    }

    private void writeValue(long stored) {
        if (history.empty()) { out.write(stored,64); return; }
        long reference=history.select(stored);
        if (reference >= 0) {
            long ref=history.value(reference);
            if (ref == stored) {
                out.write(0,2); out.write(history.slot(reference),options.windowBits());
                statistics.sameReference++; return;
            }
            out.write(1,1); out.write(history.slot(reference),options.windowBits());
            writeCenter(stored,ref,Long.numberOfTrailingZeros(ref));
            statistics.sharedTrailing++;
        } else {
            out.write(1,2); // 01: use immediate predecessor.
            if (stored == 0) {
                // Zero has 64 trailing zeros, outside a six-bit field. Reserve
                // an otherwise impossible (trail=63, lead=24) combination.
                out.write(63,6); out.write(7,3);
            } else {
                int t=Long.numberOfTrailingZeros(stored);
                out.write(t,6);
                writeCenter(stored,history.value(history.latest()),t);
            }
            statistics.fallback++;
        }
    }

    private void writeCenter(long stored,long reference,int trailing) {
        // In the predecessor fallback, matching leading bits can overlap the
        // current value's trailing-zero region. Keep the implied last 1 out
        // of the copied prefix (e.g. -0.0 following a negative subnormal).
        int lead=Math.min(Long.numberOfLeadingZeros(stored ^ reference),63-trailing),code=0;
        while (code+1 < LEADING.length && LEADING[code+1] <= lead) code++;
        int width=64-LEADING[code]-trailing-1;
        if (width < 0) throw new IllegalStateException("Invalid center width");
        out.write(code,3);
        // Copy the shared leading prefix from the reference; encode the
        // current-value center and omit its known final 1 (paper section 3.3.4).
        out.write(stored >>> (trailing+1),width);
    }

    public int size() { return count; }
    public Statistics statistics() { statistics.payloadBits=out.bits; return statistics.copy(); }

    public byte[] finish() {
        if (finished == null) {
            writeRepeats();
            byte[] payload=out.finish();
            ByteBuffer block=ByteBuffer.allocate(HEADER_BYTES+payload.length);
            block.putInt(MAGIC).put((byte)1).put((byte)options.flags()).put((byte)options.windowBits()).put((byte)0);
            block.putInt(count).putLong(out.bits).put(payload);
            finished=block.array();
        }
        return finished.clone();
    }
}
