package algorithms.Gorilla.decoder;

import algorithms.Decoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleGorillaDecoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_lead = 0;
    protected int previous_tail = 0;
    protected boolean first = true;

    public DoubleGorillaDecoder(String inputPath) {
        super(inputPath);
    }

    @Override
    public double decodeDouble() {
        if (first) {
            previous_value = in.readDouble(size);
            first = false;
        } else {
            boolean c1 = in.readBoolean();
            if (c1) {
                return previous_value;
            }

            boolean c2 = in.readBoolean();
            long xor = 0;
            if (c2) {
                int len = size - previous_lead - previous_tail;
                xor = in.readLong(len) << previous_tail;
            } else {
                int lim_lead = in.readInt(5);
                int len = in.readInt(6) + 1;
                int tail = size - len - lim_lead;
                xor = in.readLong(len) << tail;
            }
            previous_lead = BinaryTools.leadZeros(xor, size);
            previous_tail = BinaryTools.tailZeros(xor, size);
            previous_value = BinaryTools.xor(xor, previous_value);
        }
        return previous_value;
    }
}
