package algorithms.ElfPlus.encoder;

import algorithms.ElfPlus.Elf64Utils;
import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleElfPlusEncoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected long previous_long_value = 0;
    protected int previous_lead = 0;
    protected int previous_tail = 0;

    protected int previous_betaStar = 0;
    protected boolean first = true;

    public DoubleElfPlusEncoder(String outputPath) {
        super(outputPath);
    }

    protected long PlusEraser(double v) {
        long vLong = Double.doubleToRawLongBits(v);
        long vPrimeLong;

        if (v == 0.0 || Double.isInfinite(v)) {
            out.write(2,2);
            return vLong;
        }

        // C1: v is a normal or subnormal
        int[] alphaAndBetaStar = Elf64Utils.getAlphaAndBetaStar(v, previous_betaStar);
        int e = ((int) (vLong >> 52)) & 0x7ff;
        int gAlpha = Elf64Utils.getFAlpha(alphaAndBetaStar[0]) + e - 1023;
        int eraseBits = 52 - gAlpha;
        int betaStar = alphaAndBetaStar[1];
        long mask = 0xffffffffffffffffL << eraseBits;
        long delta = (~mask) & vLong;
        if (betaStar < 16 && delta != 0 && eraseBits > 4) {  // C2
            if(alphaAndBetaStar[1] == previous_betaStar){
                out.write(false);
            }else{
                out.write(alphaAndBetaStar[1] | 0x30, 6);  // case 11, 2 + 4 = 6
                previous_betaStar = betaStar;
            }
            vPrimeLong = mask & vLong;
        } else {
            out.write(2,2);
            vPrimeLong = vLong;
        }
        return vPrimeLong;
    }

    protected void ElfXor(long vLong) {
        long xor = vLong ^ previous_long_value;
        int lead = BinaryTools.leadZeros(xor, size);
        int tail = BinaryTools.tailZeros(xor, size);
        lead = Math.min(lead, 7);

        boolean c2 = (lead == previous_lead) && (tail >= previous_tail);

        if (xor != 0 && c2) { // 00 reuse
            out.write(0, 2);
            out.write(xor >> previous_tail, size - previous_lead - previous_tail);
        } else if (xor == 0) { // 01
            out.write(1, 2);
        } else {
            out.write(true);
            int len = size - lead - tail;
            if (len <= 16) {
                out.write(false);
                out.write(lead, 3);
                out.write(len - 1, 4);
            } else {
                out.write(true);
                out.write(lead, 3);
                out.write(len - 1, 6);
            }
            out.write(xor >> tail, len);
        }
        previous_lead = lead;
        previous_tail = tail;
    }

    @Override
    public int encode(double value) {
        if (first) { // first value
            out.write(value, size);
            this.previous_long_value = Double.doubleToRawLongBits(value);
            first = false;
        } else {
            long vLong = PlusEraser(value);
            double dv = Double.longBitsToDouble(vLong);
            ElfXor(vLong);
            this.previous_long_value = vLong;
        }
        return out.track_bits();
    }
}
