package algorithms.Gorilla.encoder;

import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleGorillaEncoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_lead = 0;
    protected int previous_tail = 0;
    protected boolean first = true;

    protected double total_lead = 0;
    protected double total_tail = 0;
    protected long total = 0;

    public DoubleGorillaEncoder(String outputPath) {
        super(outputPath);
    }

    @Override
    public int encode(double value) {
        total ++;
        if (first) { // first value
            out.write(value, size);
            first = false;
        } else {
            if (value == previous_value) {
                out.write(true);
                return out.track_bits();
            }
            out.write(false);

            long xor = BinaryTools.xor(value, previous_value);
            int lead = BinaryTools.leadZeros(xor, size);
            int tail = BinaryTools.tailZeros(xor, size);

            if (lead >= previous_lead && tail >= previous_tail) {
                out.write(true);
                int len = size - previous_lead - previous_tail;
                out.write(xor >>> previous_tail, len);
            } else {
                out.write(false);
                int lim_lead = Math.min(lead, 31);
                out.write(lim_lead, 5); // limited
                int len = size - lim_lead - tail;
                out.write(len - 1, 6);
                out.write(xor >>> tail, len);
            }
            previous_lead = lead;
            previous_tail = tail;
        }
        this.previous_value = value;
        return out.track_bits();
    }
}
