package algorithms.Chimp128.encoder;

import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleChimp128Encoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();

    protected double[] lbw = new double[128]; // look back window

    protected long id = 0;

    protected long mask = (1 << 14) - 1;

    protected long[] map = new long[1 << 14];
    protected int previous_lead = 0;

    public DoubleChimp128Encoder(String outputPath) {
        super(outputPath);
    }

    @Override
    public int encode(double value) {
        id++;
        long key = Double.doubleToRawLongBits(value) & mask;
        if (id == 1) { // first value
            out.write(value, size);
        } else {
            long best_id = map[(int) key];
            long xor;
            int lead, tail;
            if (best_id == 0 | id - best_id > 128) {
                best_id = id - 1;
                xor = BinaryTools.xor(value, lbw[(int) (best_id % 128)]);
                lead = BinaryTools.leadZeros(xor, size);
                out.write(true);
                if (lead != previous_lead) {
                    out.write(true);
                    int lim_lead = Math.min(lead, 7);
                    out.write(lim_lead, 3);
                    out.write(xor, size - lim_lead);
                } else {
                    out.write(false);
                    out.write(xor, size - previous_lead);
                }
            } else {
                xor = BinaryTools.xor(value, lbw[(int) (best_id % 128)]);
                lead = BinaryTools.leadZeros(xor, size);
                tail = BinaryTools.tailZeros(xor, size);
                out.write(false);
                out.write(id - best_id - 1, 7);
                if (xor != 0) {
                    out.write(true);
                    int lim_lead = Math.min(lead, 7);
                    int len = size - lim_lead - tail;
                    out.write(lim_lead, 3);
                    out.write(len - 1, 6);
                    out.write(xor >>> tail, len);
                } else {
                    out.write(false);
                }
            }
            previous_lead = lead;
        }
        lbw[(int) (id % 128)] = value;
        map[(int) key] = id;
        return out.track_bits();
    }
}
