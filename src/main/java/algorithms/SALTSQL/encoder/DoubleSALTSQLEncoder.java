package algorithms.SALTSQL.encoder;

import algorithms.Encoder;
import algorithms.SALTSQL.SALTSQLUtils;

import java.math.BigDecimal;

/**
 * Sequential SALTSQL-style double encoder.
 *
 * Bitstream:
 *   header: WIN_BITS(4) + EXPONENT_BITS(4) + ULP_BITS(4)
 *   first value in each fixed window:
 *     zeroFlag(1), or zeroFlag(0)+sign(1)+ulpPlaces(4)+exponent(11 sign-mag)+mantissaTopBits
 *   following values in the same window:
 *     encode delta = value - firstValue, where firstValue is the window base.
 */
public class DoubleSALTSQLEncoder extends Encoder {
    private static final int WIN_BITS = 7;
    private static final int EXPONENT_BITS = 5;
    private static final int ULP_BITS = 2;

    private final int winSize = 1 << WIN_BITS;
    private boolean headerWritten = false;
    private int blockCount = 0;
    private BigDecimal firstValue = BigDecimal.ZERO;

    public DoubleSALTSQLEncoder(String outputPath) {
        super(outputPath);
    }

    @Override
    public int encode(double v) {
        if (!headerWritten) {
            out.write(WIN_BITS, 4);
            out.write(EXPONENT_BITS, 4);
            out.write(ULP_BITS, 4);
            headerWritten = true;
        }

        BigDecimal value = SALTSQLUtils.toInputBigDecimal(v);
        boolean isWindowStart = (blockCount % winSize == 0);

        if (isWindowStart) {
            writeWindowStart(value);
        } else {
            BigDecimal delta = SALTSQLUtils.subtractWithOriginalPrecision(value, firstValue).stripTrailingZeros();
            writeDelta(delta, firstValue);
        }

        blockCount++;
        return out.track_bits();
    }

    private void writeWindowStart(BigDecimal value) {
        if (value.signum() == 0) {
            out.write(true); // zeroFlag = 1; decoded value is 0
            firstValue = SALTSQLUtils.FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO;
            return;
        }

        firstValue = value;
        out.write(false); // zeroFlag = 0
        out.write(value.signum() < 0 ? 1 : 0, 1);

        int ulpPlaces = Math.min(15, SALTSQLUtils.getUlpPlaces(value));
        int exponent = SALTSQLUtils.getIeeeExponent(value);
        int manBits = SALTSQLUtils.mantissaBitsToKeep(value);

        out.write(ulpPlaces, 4);
        out.write(SALTSQLUtils.encodeSignMagnitudeBits(exponent, 11), 11);
        writeMantissaTop(value.doubleValue(), manBits);
    }

    private void writeDelta(BigDecimal delta, BigDecimal baseValue) {
        if (delta.signum() == 0) {
            out.write(true); // zeroFlag = 1; value == firstValue
            return;
        }

        out.write(false); // zeroFlag = 0
        out.write(delta.signum() < 0 ? 1 : 0, 1);

        int ulpPlaces = SALTSQLUtils.getUlpPlaces(delta);
        int ulpDelta = ulpPlaces - SALTSQLUtils.getUlpPlaces(baseValue);
        long ulpBits = SALTSQLUtils.encodeSignMagnitudeBits(ulpDelta, ULP_BITS);
        out.write(ulpBits, ULP_BITS);
        if (SALTSQLUtils.decodeSignMagnitudeBits(ulpBits, ULP_BITS).overflow) {
            out.write(Math.min(15, ulpPlaces), 4);
        }

        int exponent = SALTSQLUtils.getIeeeExponent(delta);
        long expBits = SALTSQLUtils.encodeSignMagnitudeBits(exponent, EXPONENT_BITS);
        if (SALTSQLUtils.decodeSignMagnitudeBits(expBits, EXPONENT_BITS).overflow) {
            out.write(1, 1);
            out.write(SALTSQLUtils.encodeSignMagnitudeBits(exponent, 11), 11);
        } else {
            out.write(0, 1);
            out.write(expBits, EXPONENT_BITS);
        }

        int manBits = SALTSQLUtils.mantissaBitsToKeep(delta);
        writeMantissaTop(delta.doubleValue(), manBits);
    }

    private void writeMantissaTop(double value, int manBits) {
        if (manBits <= 0) return;
        if (manBits > 52) manBits = 52;
        long top = SALTSQLUtils.getDoubleMantissaTopBits(value, manBits);
        out.write(top, manBits);
    }
}
