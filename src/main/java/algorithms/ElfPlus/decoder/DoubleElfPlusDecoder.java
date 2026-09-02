package algorithms.ElfPlus.decoder;

import algorithms.Decoder;
import algorithms.ElfPlus.Elf64Utils;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleElfPlusDecoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_lead = 0;
    protected int previous_tail = 0;

    protected int previous_betaStar = 0;
    protected boolean first = true;

    public DoubleElfPlusDecoder(String inputPath) {
        super(inputPath);
    }

    protected double recover(double vPrime, long betaStar) {
        double res;
        int sp = Elf64Utils.getSP(Math.abs(vPrime));
        if (betaStar == 0) {
            res = Elf64Utils.get10iN(-sp - 1);
            if (vPrime < 0) {
                res = -res;
            }
        } else {
            int alpha = (int) (betaStar - sp - 1);
            res = Elf64Utils.roundUp(vPrime, alpha);
        }
        return res;
    }

    protected void ElfXorDecoder() {
        int c2 = in.readInt(2);

        long xor = 0;
        if (c2 == 0) {
            xor = in.readLong(size - previous_lead - previous_tail) << previous_tail;
            previous_lead = BinaryTools.leadZeros(xor, size);
            previous_lead = Math.min(previous_lead, 7);
            previous_tail = BinaryTools.tailZeros(xor, size);
        } else if (c2 == 1) {
            xor = 0;
            previous_lead = 7;
            previous_tail = size;
        } else {
            previous_lead = in.readInt(3);
            int len;
            if (c2 == 2) len = in.readInt(4) + 1;
            else len = in.readInt(6) + 1;
            previous_tail = size - len - previous_lead;
            xor = in.readLong(len) << previous_tail;
        }
        previous_value = BinaryTools.xor(xor, previous_value);

    }


    @Override
    public double decodeDouble() {
        if (first) {
            previous_value = in.readDouble(size);
            first = false;
        } else {
            boolean c1 = in.readBoolean();
            boolean c2 = false;
            if (c1) {
                c2 = in.readBoolean();
                if (c2) previous_betaStar = in.readInt(4);
            }
            ElfXorDecoder();
            if (!c1 || c2) return recover(previous_value, previous_betaStar);
        }
        return previous_value;
    }
}
