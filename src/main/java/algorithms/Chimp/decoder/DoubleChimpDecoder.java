package algorithms.Chimp.decoder;

import algorithms.Decoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleChimpDecoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_lead = 0;
    protected boolean first = true;

    public DoubleChimpDecoder(String inputPath) {
        super(inputPath);
    }

    @Override
    public double decodeDouble() {
        if (first) {
            previous_value = in.readDouble(size);
            first = false;
        } else {
            boolean c1 = in.readBoolean();
            boolean c2 = in.readBoolean();
            long xor = 0;
            if (c1) {
                if(c2){
                    int lim_lead = in.readInt(3);
                    xor = in.readLong(size - lim_lead);
                }else{
                    xor = in.readLong(size - previous_lead);
                }
            }else{
                if(c2){
                    int lim_lead = in.readInt(3);
                    int len = in.readInt(6) + 1;
                    int tail = size - lim_lead - len;
                    xor = in.readLong(len) << tail;
                }else return previous_value;
            }

            previous_lead = BinaryTools.leadZeros(xor, size);
            previous_value = BinaryTools.xor(xor, previous_value);
        }
        return previous_value;
    }
}
