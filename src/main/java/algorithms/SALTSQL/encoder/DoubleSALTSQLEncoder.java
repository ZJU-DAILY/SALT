package algorithms.SALTSQL.encoder;

import algorithms.Encoder;
import algorithms.SALTSQL.SALTSQLRawCodec;
import java.math.BigDecimal;

/** Versioned SALT+ encoder with a fixed ZERO/ESC/RAW codebook. */
public class DoubleSALTSQLEncoder extends Encoder {
    private static final int WIN_BITS = 7, EXPONENT_BITS = 5, ULP_BITS = 2;
    private int count;
    private long rawCount;
    private BigDecimal reference;
    public DoubleSALTSQLEncoder(String path) { super(path); }
    public long getRawCount() { return rawCount; }

    @Override public int encode(double value) {
        if (count == 0) {
            out.write(0, 4); // Versioned header marker; legacy WIN_BITS is nonzero.
            out.write(SALTSQLRawCodec.VERSION, 4);
            out.write(WIN_BITS, 4);
            out.write(EXPONENT_BITS, 4);
            out.write(ULP_BITS, 4);
        }
        boolean first = count % (1 << WIN_BITS) == 0;
        String bits = SALTSQLRawCodec.encode(value, reference, first, EXPONENT_BITS, ULP_BITS);
        if (bits.startsWith("01")) rawCount++;
        for (int i = 0; i < bits.length(); i++) out.write(bits.charAt(i) == '1');
        if (first) reference = SALTSQLRawCodec.reference(value);
        count++;
        return out.track_bits();
    }
}
