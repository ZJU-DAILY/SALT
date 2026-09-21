package algorithms.SALTE;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * OurE shared utilities used by encoder/decoder.
 * - Sign-magnitude helpers with "-0" overflow sentinel
 * - IEEE exponent / decimal ulp places helpers
 * - Mantissa bits calculation (merge same logic)
 * - Mantissa top bits extraction / rebuild double
 * - Misc helpers (binary formatting, remove extension, etc.)
 */
public final class SALTEUtils {

    private SALTEUtils() {}

    /** log2(10) used in ulp->binary-bits conversion. */
    public static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

    /** A decoded sign-magnitude integer plus overflow flag ("-0" sentinel). */
    public static final class SignMag {
        public final int value;
        public final boolean overflow;

        public SignMag(int value, boolean overflow) {
            this.value = value;
            this.overflow = overflow;
        }
    }

    // Basic helpers

    // Sign-magnitude helpers (with "-0" overflow sentinel)
    // Layout: [sign(1)][magnitude(totalBits-1)]
    // Overflow sentinel: sign=1 and magnitude=0 (only meaningful when magBits>0)

    /**
     * Encode sign-magnitude into low totalBits bits of a long.
     * If abs(value) is not representable in (totalBits-1) magnitude bits, returns "-0" sentinel.
     */
    public static long encodeSignMagnitudeBits(int value, int totalBits) {
        if (totalBits <= 0) return 0L;
        int magBits = totalBits - 1;
        long maxMag = (magBits <= 0) ? 0L : ((1L << magBits) - 1L);

        long absVal = Math.abs((long) value);

        long sign = (value < 0) ? 1L : 0L;

        // overflow => "-0" sentinel: sign=1, magnitude=0
        if (magBits > 0 && absVal > maxMag) {
            return 1L << magBits;
        }

        long mag = (magBits <= 0) ? 0L : (absVal & maxMag);
        return (sign << magBits) | mag;
    }

    /**
     * Decode sign-magnitude from low totalBits bits of {@code bits}.
     * Returns overflow=true iff "-0" sentinel observed.
     */
    public static SignMag decodeSignMagnitudeBits(long bits, int totalBits) {
        if (totalBits <= 0) return new SignMag(0, false);

        int magBits = totalBits - 1;
        long sign = (magBits >= 0) ? ((bits >>> magBits) & 1L) : 0L;

        long magMask;
        if (magBits <= 0) {
            magMask = 0L;
        } else if (magBits == 64) {
            magMask = -1L; // not expected in practice
        } else {
            magMask = (1L << magBits) - 1L;
        }

        long mag = bits & magMask;

        boolean overflow = (magBits > 0 && sign == 1L && mag == 0L);
        int magInt = (int) mag;

        // for overflow sentinel, value isn't used by decoder branches; set to 0
        int val = overflow ? 0 : ((sign == 1L) ? -magInt : magInt);
        return new SignMag(val, overflow);
    }

    // IEEE exponent / ulp places helpers

    /** Same behavior as your original: for 0 => -1023. */
    public static int getIeeeExponent(BigDecimal x) {
        if (x == null || x.signum() == 0) return -1023;
        return Math.getExponent(x.doubleValue());
    }

    /** Decimal "ulp places": scale after stripping trailing zeros, clamped to >=0. */
    public static int getUlpPlaces(BigDecimal x) {
        if (x == null) return 0;
        BigDecimal stripped = x.stripTrailingZeros();
        int scale = stripped.scale();
        return Math.max(scale, 0);
    }

    // Mantissa bits computation (merge same idea)

    /**
     * Encoder-side mantissa bits to keep (original getManSave BigDecimal version).
     * Output clamped to [0, 52].
     */
    public static int mantissaBitsToKeep(BigDecimal x) {
        if (x == null) throw new IllegalArgumentException("Input cannot be null");
        BigDecimal ax = x.abs();

        // |x| < 1
        if (ax.compareTo(BigDecimal.ONE) < 0) {
            int E = getIeeeExponent(ax);
            int ulp = getUlpPlaces(ax);
            double t = Math.floor(-ulp * LOG2_10);
            int bits = (int) Math.round(E - t); // t negative => E + |t|
            return clampMantissaBits(bits);
        }

        // |x| >= 1
        long intPart = ax.longValue();
        long absInt = Math.abs(intPart);
        if (intPart == Long.MIN_VALUE) absInt = Long.MAX_VALUE; // guard
        int intBits = (absInt != 0) ? (64 - Long.numberOfLeadingZeros(absInt)) : 1;

        int ulp = getUlpPlaces(ax);
        int extra = (int) Math.ceil(ulp * LOG2_10);
        int bits = intBits + extra - 1;
        return clampMantissaBits(bits);
    }

    /**
     * Decoder-side version: compute mantissa bits using exponent + ulpPlaces directly.
     * Output clamped to [0, 52].
     */
    public static int mantissaBitsToKeep(int exponent, int ulpPlaces) {
        int E = exponent;
        int ulp = Math.max(ulpPlaces, 0);

        double t = Math.floor(-ulp * LOG2_10);

        int bits;
        if (E < 0) {
            bits = (int) Math.round(E - t);
        } else {
            int intBits = E + 1;
            int extra = (int) Math.ceil(ulp * LOG2_10);
            bits = intBits + extra - 1;
        }

        return clampMantissaBits(bits);
    }

    private static int clampMantissaBits(int bits) {
        if (bits < 0) return 0;
        if (bits > 52) return 52;
        return bits;
    }

    // Mantissa extraction / build double

    /**
     * Get the top numBits bits of IEEE754 mantissa (fraction) from a double.
     * Returns as an unsigned integer in the low bits of a long.
     */
    public static long getDoubleMantissaTopBits(double value, int numBits) {
        if (numBits <= 0) return 0L;
        if (numBits > 52) numBits = 52;

        long bits = Double.doubleToRawLongBits(value);
        long mantissa = bits & ((1L << 52) - 1L);
        int shift = 52 - numBits;
        return mantissa >>> shift;
    }

    /**
     * Build a double from sign (0/1), unbiased exponent, and mantissaTop (top bits),
     * where mantissaTopBits indicates how many bits are valid in mantissaTop.
     */
    public static double buildDouble(int sign, int exponent, long mantissaTop, int mantissaTopBits) {
        if (mantissaTopBits < 0) mantissaTopBits = 0;
        if (mantissaTopBits > 52) mantissaTopBits = 52;

        long mantissa = (mantissaTopBits == 0) ? 0L : (mantissaTop << (52 - mantissaTopBits));

        int biased = exponent + 1023;
        if (biased < 0) biased = 0;
        if (biased > 2046) biased = 2046; // keep away from Inf/NaN unless you intentionally support it

        long raw = 0L;
        if (sign == 1) raw |= (1L << 63);
        raw |= ((long) biased & 0x7FFL) << 52;
        raw |= mantissa & ((1L << 52) - 1L);

        return Double.longBitsToDouble(raw);
    }

    // BigDecimal rounding helpers (decoder side)

    /**
     * Quantize like "Decimal(str(val)).quantize(1e-ulp, ROUND_UP)".
     * Java's RoundingMode.UP is away from zero; keep explicit negative branch to match.
     */
    public static BigDecimal toScaledBigDecimal(double d, int ulpPlaces) {
        BigDecimal bd = new BigDecimal(Double.toString(d));
        int scale = Math.max(ulpPlaces, 0);
        if (d >= 0.0) {
            return bd.setScale(scale, RoundingMode.UP);
        } else {
            return bd.negate().setScale(scale, RoundingMode.UP).negate();
        }
    }

    /** Keep behavior aligned with your original: plain add without forcing scale alignment. */
    public static BigDecimal addWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null) a = BigDecimal.ZERO;
        if (b == null) b = BigDecimal.ZERO;
        return a.add(b);
    }
}
