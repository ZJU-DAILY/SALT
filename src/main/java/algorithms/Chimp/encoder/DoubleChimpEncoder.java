package algorithms.Chimp.encoder;

import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleChimpEncoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_lead = 0;
    protected boolean first = true;

    public DoubleChimpEncoder(String outputPath) {
        super(outputPath);
    }

    @Override
    public int encode(double value) {
        if (first) { // first value
            out.write(value, size);
            first = false;
        } else {
            long xor = BinaryTools.xor(value, previous_value);
            int lead = BinaryTools.leadZeros(xor, size);
            int tail = BinaryTools.tailZeros(xor, size);

            if (tail <= 6) {
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
                out.write(false);
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
        this.previous_value = value;
        return out.track_bits();
    }
}
