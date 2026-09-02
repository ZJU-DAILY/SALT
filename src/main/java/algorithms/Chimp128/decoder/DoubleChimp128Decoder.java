package algorithms.Chimp128.decoder;

import algorithms.Decoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleChimp128Decoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double[] lbw = new double[128]; // look back window

    protected long id = 0;
    protected int previous_lead = 0;

    public DoubleChimp128Decoder(String inputPath) {
        super(inputPath);
    }

    @Override
    public double decodeDouble() {
        double value = 0;
        id++;
        if (id == 1) {
            value = in.readDouble(size);
        } else {
            boolean c1 = in.readBoolean();
            long xor = 0;
            long best_id;
            if (c1) {
                best_id = id - 1;
                boolean c2 = in.readBoolean();
                if (c2) {
                    int lim_lead = in.readInt(3);
                    xor = in.readLong(size - lim_lead);
                } else {
                    xor = in.readLong(size - previous_lead);
                }
            } else {
                best_id = id - 1 - in.readInt(7);
                boolean c2 = in.readBoolean();
                if (c2) {
                    int lim_lead = in.readInt(3);
                    int len = in.readInt(6) + 1;
                    int tail = size - lim_lead - len;
                    xor = in.readLong(len) << tail;
                }
            }
            previous_lead = BinaryTools.leadZeros(xor, size);
            value = BinaryTools.xor(xor, lbw[(int) (best_id % 128)]);
        }
        lbw[(int) (id % 128)] = value;
        return value;
    }
}
