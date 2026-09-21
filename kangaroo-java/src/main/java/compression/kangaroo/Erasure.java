package compression.kangaroo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Paper section 5, with interval inversion replacing the inconsistent Eq. 22. */
final class Erasure {
    static final int UNCHANGED = 0, ERASED = 1, FLIPPED = 2;
    private static final int MAX_ALPHA = 324;
    private static final int[] CEIL_LOG = new int[MAX_ALPHA + 1];
    private static final int[] ALPHA_BY_NEED;
    static {
        BigInteger p = BigInteger.ONE;
        for (int a = 1; a <= MAX_ALPHA; a++) {
            p = p.multiply(BigInteger.TEN);
            CEIL_LOG[a] = p.bitLength(); // 10^a is not a power of two for a > 0.
        }
        // Exact inverse of Eq. 21: smallest a with ceil(a*log2(10)) >= need.
        // Constant-time recovery, without Eq. 22's inconsistent floor formula.
        ALPHA_BY_NEED = new int[CEIL_LOG[MAX_ALPHA] + 1];
        int alpha = 0;
        for (int need = 0; need < ALPHA_BY_NEED.length; need++) {
            while (CEIL_LOG[alpha] < need) alpha++;
            ALPHA_BY_NEED[need] = alpha;
        }
    }

    static final class Result {
        final long stored;
        final int kind;
        final boolean rejected;
        Result(long stored, int kind, boolean rejected) { this.stored = stored; this.kind = kind; this.rejected = rejected; }
    }

    static int length(int exponent, int alpha) {
        int ceil = alpha == -1 ? -3 : CEIL_LOG[alpha];
        return Math.max(0, 52 - exponent - ceil);
    }

    static Result encode(long original, boolean enabled) {
        int field = (int) ((original >>> 52) & 2047);
        if (!enabled || field == 0 || field == 2047) return new Result(original, UNCHANGED, false);
        double value = Double.longBitsToDouble(original);
        int alpha = Math.max(0, BigDecimal.valueOf(value).stripTrailingZeros().scale());
        if (alpha > MAX_ALPHA) return new Result(original, UNCHANGED, false);
        int exponent = field - 1023;
        int length = length(exponent, alpha);
        // Do not erase implicit exponent/sign bits. The paper's formula alone
        // does not clamp its result to the 52 explicit mantissa bits.
        if (length <= 0 || length > 52) return new Result(original, UNCHANGED, false);
        long stored = original & ~BitStream.lowMask(length);
        if (Long.numberOfTrailingZeros(stored) <= Long.numberOfTrailingZeros(original))
            return new Result(original, UNCHANGED, false);
        int kind = ERASED;
        int upper = length(exponent, alpha - 1);
        if (Long.numberOfTrailingZeros(stored) >= upper) {
            int flip = upper - 1;
            if (flip < 0 || flip >= 52) return new Result(original, UNCHANGED, true);
            stored |= 1L << flip;
            kind = FLIPPED;
        }
        // Use exactly the decoder reconstruction, without a tolerance.
        if (decode(stored, kind) != original) return new Result(original, UNCHANGED, true);
        return new Result(stored, kind, false);
    }

    static int inferAlpha(int exponent, int trailing) {
        if (exponent < -1022 || exponent > 1023 || trailing < 0 || trailing > 63)
            throw new IllegalArgumentException("Invalid normal exponent/trailing count");
        int need = 52 - exponent - trailing;
        if (need >= ALPHA_BY_NEED.length)
            throw new IllegalArgumentException("Erasure precision out of range");
        int alpha = ALPHA_BY_NEED[Math.max(0, need)];
        if (!(length(exponent, alpha) <= trailing && trailing < length(exponent, alpha - 1)))
            throw new IllegalArgumentException("Invalid erasure precision interval");
        return alpha;
    }

    static long decode(long stored, int kind) {
        if (kind == UNCHANGED) return stored;
        if (kind != ERASED && kind != FLIPPED) throw new IllegalArgumentException("Invalid erasure tag");
        int field = (int) ((stored >>> 52) & 2047);
        if (field == 0 || field == 2047) throw new IllegalArgumentException("Erasure on non-normal value");
        int exponent = field - 1023;
        int alpha = inferAlpha(exponent, Long.numberOfTrailingZeros(stored));
        if (kind == FLIPPED) {
            int flip = length(exponent, alpha - 1) - 1;
            if (flip < 0 || flip >= 52 || (stored & (1L << flip)) == 0)
                throw new IllegalArgumentException("Invalid erasure flip bit");
            stored &= ~(1L << flip);
        }
        // Exact binary value -> exact decimal, then round magnitude upward.
        // No double multiply by 10^alpha, and no epsilon-based acceptance.
        BigDecimal exact = new BigDecimal(Double.longBitsToDouble(stored));
        double recovered = exact.setScale(alpha, RoundingMode.UP).doubleValue();
        return Double.doubleToRawLongBits(recovered);
    }
    private Erasure() { }
}
