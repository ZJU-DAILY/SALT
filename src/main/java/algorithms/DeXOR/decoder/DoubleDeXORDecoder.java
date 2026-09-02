package algorithms.DeXOR.decoder;

import algorithms.DeXOR.encoder.DoubleDeXOREncoder;
import algorithms.Decoder;
import algorithms.DeXOR.DeXORTools;
import enums.DataTypeEnums;

public class DoubleDeXORDecoder extends Decoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double previous_value = 0;
    protected int previous_q = 0;
    protected int previous_delta = 0;

    protected long previous_exp = 1023;
    protected int EL = 1;
    protected int contract_step = 0;

    protected double previous_alpha = 0;
    protected Method method = new Native();
    protected boolean skip = false;

    /**
     * from config
     **/
    protected int buffer_bits = 0;
    protected double[] buffer = new double[0];

    protected int rho = 8;

    protected int skip_available = -1;

    public DoubleDeXORDecoder(String inputPath) {
        super(inputPath);
    }

    public DoubleDeXORDecoder(String inputPath, String config) {
        super(inputPath, config);
        String buffer_bits_config = this.config.get("buffer_bits");
        String rho_config = this.config.get("rho");
        String skip_available_config = this.config.get("skip_available");
        if (buffer_bits_config != null) {
            buffer_bits = Integer.parseInt(buffer_bits_config);
        }
        if (rho_config != null) {
            rho = Integer.parseInt(rho_config);
        }
        if (skip_available_config != null) {
            skip_available = Integer.parseInt(skip_available_config);
        }

        if (this.buffer_bits > 0) {
            this.buffer = new double[1 << buffer_bits];
            this.method = new Buffered();
        } else if (this.skip_available >= 0) {
            this.method = new Skippable();
        }
    }

    protected double ExceptionDecode() {
        int bias = DeXORTools.getP2(EL - 1) - 1;
        long delta = in.readInt(EL) - bias;
        long lv;
        if (delta >= -bias && delta <= bias) {
            previous_exp += delta;
            lv = in.readLong(1);
            lv = (lv << 11) | previous_exp;
            long seg = in.readLong(52);
            lv = (lv << 52) | seg;

            if (EL > 1) {
                int su_bias = DeXORTools.getP2(EL - 2) - 1;
                if (delta >= -su_bias && delta <= su_bias) {
                    contract_step++;
                } else {
                    contract_step = 0;
                }
                if (contract_step == rho) {
                    EL--;
                    contract_step = 0;
                }
            }
        } else {
            lv = in.readLong(64);
            previous_exp = DeXORTools.segment(lv, 2, 12);

            if (EL < 10) {
                EL++;
                contract_step = 0;
            }
        }
        return Double.longBitsToDouble(lv);
    }

    protected abstract class Method {
        protected double decodeDouble() {
            int con = in.readInt(2);
            if (con == 3) { // overflow Exception
                return ExceptionDecode();
            }


            if (con == 0 || con == 1) {
                if (con == 0) previous_q = in.readInt(5) - 20;
                previous_delta = in.readInt(4);
                double pow = DeXORTools.getP10(previous_q + previous_delta);
                previous_alpha = DeXORTools.truncate(previous_value / pow) * pow;
            }

            long sign = previous_alpha > 0 ? 1 : -1;
            if (DeXORTools.comp(previous_alpha, 0) == 0) sign = in.readBoolean() ? 1 : -1; // sign
            long beta_star = sign * in.readLong(DeXORTools.decimalBits(previous_delta));
            double beta = beta_star * DeXORTools.getP10(previous_q);

            previous_value = previous_alpha + beta;

            return previous_value;
        }
    }

    protected class Native extends Method {
    }

    protected class Buffered extends Method {
        protected int total = 0;

        @Override
        protected double decodeDouble() {
            int con = in.readInt(2);
            if (con == 3) { // overflow Exception
                return ExceptionDecode();
            }

            int id = in.readInt(buffer_bits);
            previous_value = buffer[id];

            if (con == 0 || con == 1) {
                if (con == 0) previous_q = in.readInt(5) - 20;
                previous_delta = in.readInt(4);
            }

            double pow = DeXORTools.getP10(previous_q + previous_delta);
            previous_alpha = DeXORTools.truncate(previous_value / pow) * pow;

            long sign = previous_alpha > 0 ? 1 : -1;
            if (DeXORTools.comp(previous_alpha, 0) == 0) sign = in.readBoolean() ? 1 : -1; // sign
            long beta = sign * in.readLong(DeXORTools.decimalBits(previous_delta));
            double residual = beta * DeXORTools.getP10(previous_q);

            previous_value = previous_alpha + residual;

            buffer[total++] = previous_value;
            total %= buffer.length;

            return previous_value;
        }
    }

    protected class Skippable extends Method {
        protected int exception_times = 0;

        @Override
        protected double decodeDouble() {
            if (skip) return ExceptionDecode();
            int con = in.readInt(2);
            if (con == 3) { // overflow Exception
//            return in.readDouble(64);
                exception_times++;
                if (exception_times >= skip_available) skip = true;
                return ExceptionDecode();
            }
            exception_times = 0;

            if (con == 0 || con == 1) {
                if (con == 0) previous_q = in.readInt(5) - 20;
                previous_delta = in.readInt(4);
                double pow = DeXORTools.getP10(previous_q + previous_delta);
                previous_alpha = DeXORTools.truncate(previous_value / pow) * pow;
            }

            long sign = previous_alpha > 0 ? 1 : -1;
            if (DeXORTools.comp(previous_alpha, 0) == 0) sign = in.readBoolean() ? 1 : -1; // sign
            long beta_star = sign * in.readLong(DeXORTools.decimalBits(previous_delta));
            double beta = beta_star * DeXORTools.getP10(previous_q);

            previous_value = previous_alpha + beta;

            return previous_value;
        }
    }

    @Override
    public double decodeDouble() {
        return method.decodeDouble();
    }
}
