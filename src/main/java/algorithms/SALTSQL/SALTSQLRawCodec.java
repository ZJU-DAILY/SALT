package algorithms.SALTSQL;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;

/** SALT+ v1: fixed two-bit RAW=01, ESC=10, ZERO=11; 00 is invalid. */
public final class SALTSQLRawCodec {
    public static final int RAW = 1, ESC = 2, ZERO = 3;
    public static final int VERSION = 1;
    public static final int HEADER_BITS = 20;
    private SALTSQLRawCodec() {}

    public interface Reader { long read(int width) throws IOException; }

    public static final class Bits implements Reader {
        private final String bits;
        private int position;
        public Bits(String bits) { this.bits = bits; }
        public boolean hasRemaining() { return position < bits.length(); }
        public long read(int width) throws IOException {
            if (width < 0 || width > 64 || position + width > bits.length())
                throw new EOFException("Truncated SALT+ record");
            long value = 0;
            for (int i = 0; i < width; i++) {
                char c = bits.charAt(position++);
                if (c != '0' && c != '1') throw new IOException("Invalid bit");
                value = (value << 1) | (c - '0');
            }
            return value;
        }
    }

    public static BigDecimal reference(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).stripTrailingZeros() : null;
    }

    private static boolean same(double a, double b) {
        return (a == 0 && b == 0) || Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    public static String encode(double value, BigDecimal reference, boolean first, int expBits, int ulpBits) {
        if (Double.isFinite(value) && (first || reference != null)) {
            BigDecimal original = BigDecimal.valueOf(value).stripTrailingZeros();
            BigDecimal delta = first ? original : original.subtract(reference).stripTrailingZeros();
            StringBuilder candidate = new StringBuilder();
            try {
                if (delta.signum() == 0) {
                    append(candidate, ZERO, 2);
                } else {
                    int u = SALTSQLUtils.getUlpPlaces(delta);
                    int e = Math.getExponent(delta.doubleValue());
                    if (!Double.isFinite(delta.doubleValue()) || e < -1022 || e > 1023)
                        return raw(value);
                    append(candidate, ESC, 2);
                    append(candidate, delta.signum() < 0 ? 1 : 0, 1);
                    if (first) {
                        if (u > 15) return raw(value);
                        append(candidate, u, 4);
                        append(candidate, SALTSQLUtils.encodeSignMagnitudeBits(e, 11), 11);
                    } else {
                        writeTransition(candidate, u - SALTSQLUtils.getUlpPlaces(reference), u, ulpBits, 4, false);
                        writeTransition(candidate, e - Math.getExponent(reference.doubleValue()), e, expBits, 11, true);
                    }
                    int k = SALTSQLUtils.mantissaBitsToKeep(e, u);
                    append(candidate, SALTSQLUtils.getDoubleMantissaTopBits(delta.doubleValue(), k), k);
                }
                Bits reader = new Bits(candidate.toString());
                double recovered = decode(reader, reference, first, expBits, ulpBits);
                if (!reader.hasRemaining() && same(value, recovered)) return candidate.toString();
            } catch (IllegalArgumentException | ArithmeticException | IOException rejected) {
                // Unrepresentable fields or failed reconstruction take the RAW path.
            }
        }
        return raw(value);
    }

    private static void writeTransition(StringBuilder out, int difference, int absolute,
                                        int width, int absoluteWidth, boolean signed) {
        long encoded = SALTSQLUtils.encodeSignMagnitudeBits(difference, width);
        append(out, encoded, width);
        if (SALTSQLUtils.decodeSignMagnitudeBits(encoded, width).overflow) {
            if ((!signed && (absolute < 0 || absolute >= (1 << absoluteWidth)))
                    || (signed && Math.abs(absolute) >= (1 << (absoluteWidth - 1))))
                throw new IllegalArgumentException("Structural field overflow");
            append(out, signed ? SALTSQLUtils.encodeSignMagnitudeBits(absolute, absoluteWidth) : absolute, absoluteWidth);
        }
    }

    public static double decode(Reader in, BigDecimal reference, boolean first, int expBits, int ulpBits)
            throws IOException {
        int mode = (int) in.read(2);
        if (mode == RAW) return Double.longBitsToDouble(in.read(64));
        if (!first && reference == null) throw new IOException("Non-finite reference requires RAW");
        if (mode == ZERO) return first ? 0.0 : reference.doubleValue();
        if (mode != ESC) throw new IOException("Invalid SALT+ codeword: " + mode);
        int sign = (int) in.read(1);
        int u, e;
        if (first) {
            u = (int) in.read(4);
            SALTSQLUtils.SignMag exponent = SALTSQLUtils.decodeSignMagnitudeBits(in.read(11), 11);
            if (exponent.overflow) throw new IOException("Invalid absolute exponent");
            e = exponent.value;
        } else {
            u = readTransition(in, SALTSQLUtils.getUlpPlaces(reference), ulpBits, 4, false);
            e = readTransition(in, Math.getExponent(reference.doubleValue()), expBits, 11, true);
        }
        if (u < 0 || e < -1022 || e > 1023) throw new IOException("Invalid structural fields");
        int k = SALTSQLUtils.mantissaBitsToKeep(e, u);
        double delta = SALTSQLUtils.buildDouble(sign, e, in.read(k), k);
        BigDecimal decoded = SALTSQLUtils.toScaledBigDecimal(delta, u);
        return (first ? decoded : reference.add(decoded)).doubleValue();
    }

    private static int readTransition(Reader in, int base, int width, int absoluteWidth, boolean signed)
            throws IOException {
        SALTSQLUtils.SignMag d = SALTSQLUtils.decodeSignMagnitudeBits(in.read(width), width);
        if (!d.overflow) return base + d.value;
        long raw = in.read(absoluteWidth);
        if (!signed) return (int) raw;
        SALTSQLUtils.SignMag absolute = SALTSQLUtils.decodeSignMagnitudeBits(raw, absoluteWidth);
        if (absolute.overflow) throw new IOException("Invalid absolute exponent");
        return absolute.value;
    }

    public static String raw(double value) {
        StringBuilder bits = new StringBuilder(66);
        append(bits, RAW, 2);
        append(bits, Double.doubleToRawLongBits(value), 64);
        return bits.toString();
    }

    private static void append(StringBuilder out, long value, int width) {
        for (int i = width - 1; i >= 0; i--) out.append((value >>> i) & 1L);
    }
}
