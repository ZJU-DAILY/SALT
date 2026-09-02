package algorithms.Camel.decoder;

import algorithms.Camel.CamelTools;
import algorithms.Decoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

public class DoubleCamelDecoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected long previous_integer = 0;


    protected boolean first = true;

    public DoubleCamelDecoder(String inputPath) {
        super(inputPath);
    }

    protected long integer_decode() {
        long diff = in.readLong(2);
        if (diff <= 2) return previous_integer + diff - 1;
        long sign = in.readBoolean() ? 1 : -1;
        boolean gt8 = in.readBoolean();
        if (gt8) {
            diff = sign * in.readLong(16);
        } else {
            diff = sign * in.readLong(3);
        }
        return previous_integer + diff;
    }

    protected double decimal_decode() {
        int l = in.readInt(2) + 1;
        long ldxor = 0;
        boolean c1 = in.readBoolean();
        long vd = 0;
        if (c1) {
            long center = in.readLong(l);
            vd = center << (52 - l);
        }

        if (l == 1) {
            ldxor = in.readLong(3);
        } else if (l == 2) {
            boolean gt8 = in.readBoolean();
            ldxor = in.readLong(gt8 ? 5 : 3);
        } else if (l == 3) {
            int[] cost_bits = new int[]{1, 3, 5, -l + CamelTools.calculate_max(l)};
            int code = in.readInt(2);
            ldxor = in.readLong(cost_bits[code]);
        } else if (l == 4) {
            int[] cost_bits = new int[]{4, 6, 8, -l + CamelTools.calculate_max(l)};
            int code = in.readInt(2);
            ldxor = in.readLong(cost_bits[code]);
        }

        double dxor = (double) ldxor / CamelTools.quick_pow10(l);


        if (c1) {
            dxor = BinaryTools.xor(vd, 1 + dxor) - 1;
        }
        return dxor;
    }

    @Override
    public double decodeDouble() {
        double value = 0;
        if (first) {
            value = in.readDouble(size);
            previous_integer = CamelTools.truncate(value);
            first = false;
        } else {
            previous_integer = integer_decode();
            value = previous_integer + decimal_decode();
        }
        return value;
    }
}
