package algorithms.ALP.decoder;

import algorithms.ALP.ALPTools;
import algorithms.Decoder;

public class DoubleALPDecoder extends Decoder {
    protected double[] buffer = new double[1024];
    protected int available = 0;

    public DoubleALPDecoder(String inputPath) {
        super(inputPath);
    }

    protected long[] Decompress_FFOR() {
        long[] res = new long[1024];

        int cost = in.readInt(8);
        long minv = in.readLong(64);

        for (int i = 0; i < 1024; i++) {
            long v = in.readLong(cost);
            res[i] = v + minv;
        }
        return res;
    }

    protected void decompress() {
        int e = in.readInt(5);
        int f = in.readInt(5);

        int[] exc_id = new int[1024];
        double[] exc_vec = new double[1024];
        long[] enc_vec = new long[1024];

        int exc_num = in.readInt(11);

        for (int j = 0; j < exc_num; j++) {
            exc_id[j] = in.readInt(10);
            exc_vec[j] = in.readDouble(64);
        }

        enc_vec = Decompress_FFOR();

        for (int j = 0; j < 1024; j++) {
            buffer[j] = enc_vec[j] * ALPTools.getP10(-e) * ALPTools.getP10(f);
        }

        for (int j = 0; j < exc_num; j++) {
            buffer[exc_id[j]] = exc_vec[j];
        }

        available += 1024;
    }

    @Override
    public double decodeDouble() {
        if(available == 0){
            decompress();
        }
        double res = buffer[1024-available];
        available --;
        return res;
    }
}
