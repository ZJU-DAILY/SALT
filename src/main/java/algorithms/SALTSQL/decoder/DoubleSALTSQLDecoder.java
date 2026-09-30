package algorithms.SALTSQL.decoder;

import algorithms.Decoder;
import algorithms.SALTSQL.SALTSQLRawCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import algorithms.SALTSQL.SALTSQLUtils;

import java.math.BigDecimal;

/** Decoder matching DoubleSALTSQLLEncoder. */
public class DoubleSALTSQLDecoder extends Decoder {
    private boolean headerRead = false;
    private boolean rawFormat;
    private int WIN_BITS;
    private int EXPONENT_BITS;
    private int ULP_BITS;
    private int winSize;

    private int blockCount = 0;
    private BigDecimal firstValue = BigDecimal.ZERO;

    public DoubleSALTSQLDecoder(String inputPath) {
        super(inputPath);
    }

    @Override
    public double decodeDouble() {
        if (!headerRead) {
            readHeader();
        }

        boolean isWindowStart = (blockCount % winSize == 0);
        if (rawFormat) {
            try {
                double value = SALTSQLRawCodec.decode(in::readLong, firstValue, isWindowStart, EXPONENT_BITS, ULP_BITS);
                if (isWindowStart) firstValue = SALTSQLRawCodec.reference(value);
                blockCount++;
                return value;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        BigDecimal value = isWindowStart ? readWindowStart() : readDeltaValue();
        blockCount++;
        return value.doubleValue();
    }

    private void readHeader() {
        WIN_BITS = in.readInt(4);
        if (WIN_BITS == 0) {
            int version = in.readInt(4);
            if (version != SALTSQLRawCodec.VERSION) throw new IllegalArgumentException("Unsupported SALT+ version: " + version);
            rawFormat = true;
            WIN_BITS = in.readInt(4);
        }
        EXPONENT_BITS = in.readInt(4);
        ULP_BITS = in.readInt(4);
        winSize = 1 << WIN_BITS;
        blockCount = 0;
        firstValue = BigDecimal.ZERO;
        headerRead = true;
    }

    private BigDecimal readWindowStart() {
        boolean zeroFlag = in.readBoolean();
        if (zeroFlag) {
            firstValue = SALTSQLUtils.FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO;
            return BigDecimal.ZERO;
        }

        int sign = in.readInt(1);
        int ulpPlaces = in.readInt(4);
        long expBits = in.readLong(11);
        int exponent = SALTSQLUtils.decodeSignMagnitudeBits(expBits, 11).value;

        int manBits = SALTSQLUtils.mantissaBitsToKeep(exponent, ulpPlaces);
        long mantissaTop = manBits <= 0 ? 0L : in.readLong(manBits);
        double d = SALTSQLUtils.buildDouble(sign, exponent, mantissaTop, manBits);
        BigDecimal value = SALTSQLUtils.toScaledBigDecimal(d, ulpPlaces).stripTrailingZeros();
        firstValue = value;
        return value;
    }

    private BigDecimal readDeltaValue() {
        boolean zeroFlag = in.readBoolean();
        if (zeroFlag) {
            return firstValue;
        }

        int sign = in.readInt(1);

        long ulpDeltaBits = in.readLong(ULP_BITS);
        SALTSQLUtils.SignMag ulpDelta = SALTSQLUtils.decodeSignMagnitudeBits(ulpDeltaBits, ULP_BITS);
        int ulpPlaces;
        if (ulpDelta.overflow) {
            ulpPlaces = in.readInt(4);
        } else {
            ulpPlaces = Math.max(0, SALTSQLUtils.getUlpPlaces(firstValue) + ulpDelta.value);
        }

        int expFlag = in.readInt(1);
        int expLen = (expFlag == 0) ? EXPONENT_BITS : 11;
        long expBits = in.readLong(expLen);
        int exponent = SALTSQLUtils.decodeSignMagnitudeBits(expBits, expLen).value;

        int manBits = SALTSQLUtils.mantissaBitsToKeep(exponent, ulpPlaces);
        long mantissaTop = manBits <= 0 ? 0L : in.readLong(manBits);
        double deltaDouble = SALTSQLUtils.buildDouble(sign, exponent, mantissaTop, manBits);
        BigDecimal delta = SALTSQLUtils.toScaledBigDecimal(deltaDouble, ulpPlaces).stripTrailingZeros();

        return SALTSQLUtils.addWithOriginalPrecision(firstValue, delta).stripTrailingZeros();
    }
}
