package algorithms.Camel.encoder;

import algorithms.Camel.CamelTools;
import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleCamelEncoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected long previous_integer = 0;
    protected boolean first = true;

    public DoubleCamelEncoder(String outputPath) {
        super(outputPath);
    }

    protected void integer_encode(long integer) {
        long diff = integer - previous_integer;
        if (diff >= -1 && diff <= 1) {
            out.write(diff + 1, 2);
        } else {
            out.write(3, 2);
            out.write(diff >= 0);
            diff = Math.abs(diff);
            out.write(diff >= 8);
            out.write(diff, diff >= 8 ? 16 : 3);
        }
        this.previous_integer = integer;
    }

    protected void decimal_compression(double value, double dec) {
        int l = CamelTools.decimal_count(value);
        out.write(l - 1, 2);
        double dxor = dec;
        if (dec >= CamelTools.quick_pow2(-l)) {
            out.write(true);
            dxor = CamelTools.calculate_dxor(dec, l);
            long vd = BinaryTools.xor(1 + dec, 1 + dxor);
            out.write(vd >>> (52 - l), l);
        } else out.write(false);

        long ldxor = Math.round(dxor * CamelTools.quick_pow10(l));

        if (l == 0) return;
        else if (l == 1) out.write(ldxor, 3);
        else if (l == 2) {
            boolean gt8 = ldxor >= 8;
            out.write(gt8);
            out.write(ldxor, gt8 ? 5 : 3);
        } else if (l == 3) {
            int[] thresholds = new int[]{2, 8, 32};
            int[] cost_bits = new int[]{1, 3, 5, -l + CamelTools.calculate_max(l)};

            int code = 0;
            for (int i = 0; i <= 3; i++) {
                code = i;
                if (i < 3 && ldxor < thresholds[i]) break;
            }

            out.write(code, 2);
            out.write(ldxor, cost_bits[code]);
        } else if (l == 4) {
            int[] thresholds = new int[]{16, 64, 256};
            int[] cost_bits = new int[]{4, 6, 8, -l + CamelTools.calculate_max(l)};

            int code = 0;
            for (int i = 0; i <= 3; i++) {
                code = i;
                if (i < 3 && ldxor < thresholds[i]) break;
            }

            out.write(code, 2);
            out.write(ldxor, cost_bits[code]);
        }
    }

    protected void Camel(double value, long integer) {
        double dec = value - integer;
        integer_encode(integer);
        decimal_compression(value, dec);

    }

    @Override
    public int encode(double value) {
        long integer = (long) Math.floor(value);
        if (first) { // first value
            out.write(value, size);
            first = false;
        } else {
            Camel(value, integer);
        }
        this.previous_integer = integer;
        return out.track_bits();
    }
}

// source code https://github.com/yoyo185644/camel

//    } else {

//    out.writeInt(decimal_count-1, 2); // 保存字节数 00-1 01-2 10-3 11-4

//        size += decimal_count;// Store the meaningful bits of XOR

//    } else {  // m就为原来的值

//        }  else {

//            out.writeInt(m-8, 4); // "bug here" by lcy

//        }else if (m < 8){

//        }else if (m < 32) {

//        }else {

//        }else if (m < 64){

//        }else if (m < 256) {

//        }else {

//        out.writeInt((diff + 1), 2); // Map -1 to 0, 0 to 1, 1 to 2 respectively

//    } else{
//        out.writeInt(3, 2); // //11

//        } else {

//            out.writeInt(0, 1); // 0

//        } else {
//            out.writeInt(1, 1); //1  // [8,...)
//            out.writeInt(diff, 16); // 暂用16个字节表示

