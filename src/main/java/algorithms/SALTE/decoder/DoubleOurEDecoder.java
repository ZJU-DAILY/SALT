package algorithms.SALTE.decoder;

import algorithms.Decoder;
import algorithms.SALTE.SALTEUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DoubleOurEDecoder extends Decoder {

    // ----- codebook fixed widths (must match encoder) -----
    private static final int CODEBOOK_ULP_BITS = 5;
    private static final int CODEBOOK_EXP_BITS = 11;

    // meta
    private boolean headerRead = false;
    private boolean rawEnabled = false;
    private int WIN_BITS;
    private int EXPONENT_BITS;
    private int ULP_BITS;
    private int BLOCK_SIZE;

    // derived
    private int winCapacity;

    // windows
    private BigDecimal[] valueWin;
    private BigDecimal[] deltaWin;
    private int winIndex = 0;

    // block progress
    private int blockCount = 0;

    // steady-state codebook
    private final List<Key> codebook = new ArrayList<>();
    private int minCountBits = 0;

    // codebook key (same semantics as encoder)
    private static final class Key {
        final int s; // 0 positive, 1 negative
        final int u; // ulpDelta
        final int e; // expDelta
        Key(int s, int u, int e) { this.s = s; this.u = u; this.e = e; }
    }

    public DoubleOurEDecoder(String inputPath) {
        super(inputPath);
    }

    @Override
    public double decodeDouble() {
        if (!headerRead) {
            readHeaderAndInit();
        }

        // Codebook segment inserted BEFORE decoding the value when (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1)
        if (BLOCK_SIZE > 0 && (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1)) {
            readCodebookSegment();
        }

        int bestI = in.readInt(WIN_BITS);

        BigDecimal prevValue = valueWin[(winIndex + bestI) % winCapacity];
        BigDecimal prevDelta = deltaWin[(winIndex + bestI) % winCapacity];

        BigDecimal delta;

        if (!rawEnabled && blockCount < BLOCK_SIZE - 1) {
            // warmup:
            // [WIN][zeroFlag:1] OR [WIN][0][sign][expDelta][(exp11?)] [ulpDelta][(ulpPlaces4?)] [mantissaTopBits]
            boolean zeroFlag = in.readBoolean();
            if (zeroFlag) {
                delta = BigDecimal.ZERO;
            } else {
                int sign = in.readInt(1);

                int exponent = decodeExponent(prevDelta);
                int ulpPlaces = decodeUlpPlaces(prevDelta);

                int manBits = SALTEUtils.mantissaBitsToKeep(exponent, ulpPlaces);
                long mantissaTop = (manBits <= 0) ? 0L : in.readLong(manBits);

                double deltaDouble = SALTEUtils.buildDouble(sign, exponent, mantissaTop, manBits);
                delta = SALTEUtils.toScaledBigDecimal(deltaDouble, ulpPlaces).stripTrailingZeros();
            }
        } else {
            // steady-state:
            // [WIN][code:minCountBits] then:
            // - zeroCode => delta=0
            // - escapeCode => [sign][expDelta][(exp11?)] [ulpDelta][(ulpPlaces4?)] [mantissa]
            // - else => implied (sign/ulpDelta/expDelta) from codebook, then [mantissa]
            int code = in.readInt(minCountBits);

            int zeroCode = (1 << minCountBits) - 1;
            int escapeCode = (1 << minCountBits) - 2;
            if (rawEnabled && code == (1 << minCountBits) - 3) return readRaw();

            if (code == zeroCode) {
                delta = BigDecimal.ZERO;
            } else {
                int sign;
                int exponent;
                int ulpPlaces;

                if (code == escapeCode) {
                    sign = in.readInt(1);
                    exponent = decodeExponent(prevDelta);
                    ulpPlaces = decodeUlpPlaces(prevDelta);
                } else {
                    // codebook hit
                    Key k = codebook.get(code);
                    sign = k.s;

                    int prevExp = SALTEUtils.getIeeeExponent(prevDelta);
                    exponent = prevExp + k.e;

                    int prevUlp = SALTEUtils.getUlpPlaces(prevDelta);
                    ulpPlaces = Math.max(prevUlp + k.u, 0);
                }

                int manBits = SALTEUtils.mantissaBitsToKeep(exponent, ulpPlaces);
                long mantissaTop = (manBits <= 0) ? 0L : in.readLong(manBits);

                double deltaDouble = SALTEUtils.buildDouble(sign, exponent, mantissaTop, manBits);
                delta = SALTEUtils.toScaledBigDecimal(deltaDouble, ulpPlaces).stripTrailingZeros();
            }
        }

        // value = prevValue + delta (keep "original precision" behavior)
        BigDecimal value = SALTEUtils.addWithOriginalPrecision(prevValue, delta);

        // window update (same rule as encoder: only non-zero delta advances)
        if (delta.signum() != 0) {
            valueWin[winIndex] = value;
            deltaWin[winIndex] = delta;
            winIndex = (winIndex + 1) % winCapacity;
        }

        blockCount++;
        return value.doubleValue();
    }

    // Header + init

    private void readHeaderAndInit() {
        WIN_BITS = in.readInt(4);
        if (WIN_BITS == 15) {
            int version = in.readInt(4);
            if (version != 2) throw new IllegalArgumentException("Unsupported SALTE RAW version: " + version);
            rawEnabled = true;
            WIN_BITS = in.readInt(4);
        }
        EXPONENT_BITS = in.readInt(4);
        ULP_BITS = in.readInt(4);
        BLOCK_SIZE = in.readInt(20);

        winCapacity = 1 << WIN_BITS;
        valueWin = new BigDecimal[winCapacity];
        deltaWin = new BigDecimal[winCapacity];

        Arrays.fill(valueWin, BigDecimal.valueOf(0.1));
        Arrays.fill(deltaWin, BigDecimal.valueOf(0.1));
        winIndex = 0;
        blockCount = 0;

        codebook.clear();
        minCountBits = rawEnabled ? 2 : 0;

        headerRead = true;
    }

    // Codebook segment
    // Format:
    //   [minCountBits:5][size:16]
    //   each entry: [sign:1][ulpDelta:5 sign-mag][expDelta:11 sign-mag]

    private void readCodebookSegment() {
        minCountBits = in.readInt(5);
        int size = in.readInt(16);

        codebook.clear();
        for (int i = 0; i < size; i++) {
            int s = in.readInt(1);

            long uBits = in.readLong(CODEBOOK_ULP_BITS);
            SALTEUtils.SignMag uSM = SALTEUtils.decodeSignMagnitudeBits(uBits, CODEBOOK_ULP_BITS);

            long eBits = in.readLong(CODEBOOK_EXP_BITS);
            SALTEUtils.SignMag eSM = SALTEUtils.decodeSignMagnitudeBits(eBits, CODEBOOK_EXP_BITS);

            // codebook entries should never be overflow-sentinel in our encoder constraints
            codebook.add(new Key(s, uSM.value, eSM.value));
        }
    }

    // Delta-field decoding helpers (match encoder rules)

    /**
     * Decode exponent:
     *  - normal: exponent = prevExp + expDelta
     *  - overflow sentinel ("-0"): next 11 bits contain absolute exponent (sign-magnitude 11)
     */
    private int decodeExponent(BigDecimal prevDelta) {
        long expDeltaBits = in.readLong(EXPONENT_BITS);
        SALTEUtils.SignMag expDeltaSM = SALTEUtils.decodeSignMagnitudeBits(expDeltaBits, EXPONENT_BITS);

        int prevExp = SALTEUtils.getIeeeExponent(prevDelta);
        if (!expDeltaSM.overflow) {
            return prevExp + expDeltaSM.value;
        }

        long exp11Bits = in.readLong(CODEBOOK_EXP_BITS);
        SALTEUtils.SignMag exp11 = SALTEUtils.decodeSignMagnitudeBits(exp11Bits, CODEBOOK_EXP_BITS);
        return exp11.value;
    }

    private double readRaw() {
        double value = Double.longBitsToDouble(in.readLong(64));
        blockCount++; // RAW consumes a record, but never changes reference/delta windows.
        return value;
    }

    /**
     * Decode ulpPlaces:
     *  - normal: ulp = max(prevUlp + ulpDelta, 0)
     *  - overflow sentinel ("-0"): next 4 bits contain absolute ulpPlaces (unsigned)
     */
    private int decodeUlpPlaces(BigDecimal prevDelta) {
        long ulpDeltaBits = in.readLong(ULP_BITS);
        SALTEUtils.SignMag ulpDeltaSM = SALTEUtils.decodeSignMagnitudeBits(ulpDeltaBits, ULP_BITS);

        int prevUlp = SALTEUtils.getUlpPlaces(prevDelta);
        if (!ulpDeltaSM.overflow) {
            return Math.max(prevUlp + ulpDeltaSM.value, 0);
        }

        return in.readInt(4);
    }
}
