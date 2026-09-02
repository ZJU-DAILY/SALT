package algorithms.SALTSQL;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared helpers for the SALTSQL encoder/decoder. */
public final class SALTSQLUtils {
    private SALTSQLUtils() {}

    public static final BigDecimal FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO = new BigDecimal("0.1");
    public static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

    /** Decoded sign-magnitude integer. overflow=true means the special "-0" sentinel was read. */
    public static final class SignMag {
        public final int value;
        public final boolean overflow;
        public SignMag(int value, boolean overflow) {
            this.value = value;
            this.overflow = overflow;
        }
    }

    public static BigDecimal toInputBigDecimal(double v) {
        return new BigDecimal(Double.toString(v)).stripTrailingZeros();
    }

    public static int getIeeeExponent(BigDecimal x) {
        if (x == null || x.signum() == 0) return -1023;
        return Math.getExponent(x.doubleValue());
    }

    public static int getUlpPlaces(BigDecimal x) {
        if (x == null) return 0;
        int scale = x.stripTrailingZeros().scale();
        return Math.max(scale, 0);
    }

    public static BigDecimal subtractWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) throw new IllegalArgumentException("Arguments cannot be null");
        int p = Math.max(Math.max(a.scale(), 0), Math.max(b.scale(), 0));
        return a.subtract(b).setScale(p, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static BigDecimal addWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) throw new IllegalArgumentException("Arguments cannot be null");
        int p = Math.max(Math.max(a.scale(), 0), Math.max(b.scale(), 0));
        return a.add(b).setScale(p, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static int mantissaBitsToKeep(BigDecimal x) {
        if (x == null) throw new IllegalArgumentException("Input cannot be null");
        if (x.signum() == 0) return 0;
        BigDecimal ax = x.abs();
        int bits;
        if (ax.compareTo(BigDecimal.ONE) < 0) {
            int e = getIeeeExponent(ax);
            int ulp = getUlpPlaces(ax);
            double t = Math.floor(-ulp * LOG2_10);
            bits = (int) Math.round(e - t);
        } else {
            long intPart = ax.longValue();
            long absInt = Math.abs(intPart);
            if (intPart == Long.MIN_VALUE) absInt = Long.MAX_VALUE;
            int intBits = (absInt != 0) ? (64 - Long.numberOfLeadingZeros(absInt)) : 1;
            int extra = (int) Math.ceil(getUlpPlaces(ax) * LOG2_10);
            bits = intBits + extra - 1;
        }
        return clampMantissaBits(bits);
    }

    public static int mantissaBitsToKeep(int exponent, int ulpPlaces) {
        int ulp = Math.max(ulpPlaces, 0);
        int bits;
        if (exponent < 0) {
            double t = Math.floor(-ulp * LOG2_10);
            bits = (int) Math.round(exponent - t);
        } else {
            int intBits = Math.max(1, exponent + 1);
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

    public static long encodeSignMagnitudeBits(int value, int totalBits) {
        if (totalBits <= 0) return 0L;
        int magBits = totalBits - 1;
        long maxMag = (magBits <= 0) ? 0L : ((1L << magBits) - 1L);
        long absVal = Math.abs((long) value);
        if (magBits > 0 && absVal > maxMag) {
            return 1L << magBits; // "-0" overflow sentinel
        }
        long sign = value < 0 ? 1L : 0L;
        long mag = (magBits <= 0) ? 0L : (absVal & maxMag);
        return (sign << magBits) | mag;
    }

    public static SignMag decodeSignMagnitudeBits(long bits, int totalBits) {
        if (totalBits <= 0) return new SignMag(0, false);
        int magBits = totalBits - 1;
        long sign = (bits >>> magBits) & 1L;
        long magMask = (magBits <= 0) ? 0L : ((1L << magBits) - 1L);
        long mag = bits & magMask;
        boolean overflow = magBits > 0 && sign == 1L && mag == 0L;
        int value = overflow ? 0 : (sign == 1L ? -(int) mag : (int) mag);
        return new SignMag(value, overflow);
    }

    public static long getDoubleMantissaTopBits(double value, int numBits) {
        if (numBits <= 0) return 0L;
        if (numBits > 52) numBits = 52;
        long bits = Double.doubleToRawLongBits(value);
        long mantissa = bits & ((1L << 52) - 1L);
        return mantissa >>> (52 - numBits);
    }

    public static double buildDouble(int sign, int exponent, long mantissaTop, int mantissaTopBits) {
        if (mantissaTopBits < 0) mantissaTopBits = 0;
        if (mantissaTopBits > 52) mantissaTopBits = 52;
        long mantissa = mantissaTopBits == 0 ? 0L : (mantissaTop << (52 - mantissaTopBits));
        int biased = exponent + 1023;
        if (biased < 0) biased = 0;
        if (biased > 2046) biased = 2046;
        long raw = 0L;
        if (sign == 1) raw |= 1L << 63;
        raw |= ((long) biased & 0x7FFL) << 52;
        raw |= mantissa & ((1L << 52) - 1L);
        return Double.longBitsToDouble(raw);
    }

    public static BigDecimal toScaledBigDecimal(double value, int ulpPlaces) {
        BigDecimal d = new BigDecimal(Double.toString(value));
        int scale = Math.max(ulpPlaces, 0);
        BigDecimal rounded;
        if (value >= 0.0) {
            rounded = d.setScale(scale, RoundingMode.UP);
        } else {
            rounded = d.negate().setScale(scale, RoundingMode.UP).negate();
        }
        return rounded.stripTrailingZeros();
    }
}
