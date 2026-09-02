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

//public int countDecimalPlaces(BigDecimal value) {
//    String valueStr = value.toString();
//    int decimalPointIndex = valueStr.indexOf('.');
//
//    if (decimalPointIndex >= 0) {
//        return valueStr.length() - decimalPointIndex - 1;
//    } else {
//        // No decimal point, so there are no decimal places
//        return 0;
//    }
//}

//private int compressDecimalValue(long decimal_value, int decimal_count) {
//    // 计算小数位数
//    out.writeInt(decimal_count-1, 2); // 保存字节数 00-1 01-2 10-3 11-4
//    size += 2;
//    // 计算m的值
//    long thread = threshold[decimal_count-1];
//    int m = (int) decimal_value;
//    size += 1;
//    if (decimal_value - thread >= 0) {  // 计算m的值
//        // 标志位：是否计算m的值
//        out.writeBit(true);
//        m = (int) (decimal_value % thread);
//        // 对于m进行XOR操作
//        long xor = (Double.doubleToLongBits((double)decimal_value/powers[decimal_count-1]+1)) ^ Double.doubleToLongBits(((double) m/powers[decimal_count-1]+1));
//        // 保存小数位数长度的centerBits 保存decimal_count （四位最多就是1000）
//        out.writeLong(xor >>> 52 - decimal_count, decimal_count);
//        size += decimal_count;// Store the meaningful bits of XOR
//
//    } else {  // m就为原来的值
//        out.writeBit(false);
//    }
//
//    // 保存m的值
//    if (decimal_count == 1) { // 如果是1 直接往后读decimal_count+1位
//        out.writeInt(m, 3);
//        size += 3;
//        return this.size;
//    }
//    if (decimal_count ==2) {
//        if (m < 8) {
//            out.writeInt(0, 1);
//            out.writeInt(m, 3);
//            size += 4;
//            return this.size;
//        }  else {
//            out.writeInt(1, 1);
//            out.writeInt(m-8, 4); // "bug here" by lcy
//            size += 5;
//            return this.size;
//        }
//    }
//    if (decimal_count == 3) {
//        if (m < 2) {
//            out.writeInt(0, 2);
//            out.writeInt(m, 1);
//            size += 3;
//            return this.size;
//        }else if (m < 8){
//            out.writeInt(1, 2);
//            out.writeInt(m, 3);
//            size += 5;
//            return this.size;
//        }else if (m < 32) {
//            out.writeInt(2, 2);
//            out.writeInt(m, 5);
//            size += 7;
//            return this.size;
//        }else {
//            out.writeInt(3, 2);
//            out.writeInt(m, mValueBits[decimal_count-1]);
//            size += 2;
//            size += mValueBits[decimal_count-1];
//            return this.size;
//        }
//    }
//    if (decimal_count >= 4){
//        if (m < 16) {
//            out.writeInt(0, 2);
//            out.writeInt(m, 4);
//            size += 6;
//            return this.size;
//        }else if (m < 64){
//            out.writeInt(1, 2);
//            out.writeInt(m, 6);
//            size += 8;
//            return this.size;
//        }else if (m < 256) {
//            out.writeInt(2, 2);
//            out.writeInt(m, 8);
//            size += 10;
//            return this.size;
//        }else {
//            out.writeInt(3, 2);
//            out.writeInt(m, mValueBits[decimal_count-1]);
//            size += 2;
//            size += mValueBits[decimal_count-1];
//            return this.size;
//        }
//
//    }
//
//    return this.size;
//
//}

//private int compressIntegerValue(long int_value) {
//
//    int diff = (int)(int_value - storedVal) ;
//    size += 2;
//    storedVal = int_value;
//    if (diff >= -1 && diff <= 1) {
//        out.writeInt((diff + 1), 2); // Map -1 to 0, 0 to 1, 1 to 2 respectively
//        return this.size;
//    } else{
//        out.writeInt(3, 2); // //11
//        if (diff < 0){
//            out.writeBit(false);
//            diff = -diff;
//        } else {
//            out.writeBit(true);
//
//        }
//        size += 1;
//        if (diff >=2 && diff < 8) { // [4,8)
//            out.writeInt(0, 1); // 0
//            out.writeInt(diff, 3);
//            size += 4;
//            return this.size;
//        } else {
//            out.writeInt(1, 1); //1  // [8,...)
//            out.writeInt(diff, 16); // 暂用16个字节表示
//            size += 17;
//            return this.size;
//        }
//    }
//
//
////        return this.size;
//
//
//}
