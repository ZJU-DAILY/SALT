package algorithms.Camel;

public class CamelTools {
    private static final double eps = 1e-5;
    private static final int[] cost = new int[]{0, 4, 7, 10, 14, 17, 20, 24, 27, 30, 34, 37, 40, 44, 47, 50};
    private static final double[] i_pow2 = new double[]{1, 0.5, 0.25, 0.125, 0.0625};

    private static final long[] pow10 = new long[]{1, 10, 100, 1000, 10000};

    public static double quick_pow2(int exp) {
        return i_pow2[-exp];
    }

    public static long quick_pow10(int exp) {
        return pow10[exp];
    }

    public static int decimal_count(double value) {
        String valueStr = Double.toString(value);
        int decimalPointIndex = valueStr.indexOf('.');

        if (decimalPointIndex >= 0) {
            return valueStr.length() - decimalPointIndex - 1;
        } else {
            // No decimal point, so there are no decimal places
            return 0;
        }

    }

    public static int acc_decimal_count(double value, int lim) {
        int pow = 1;
        for (int i = 0; i < lim; i++) {
            double nv = value * pow;
            double lv = Math.round(nv);
            if (Math.abs(nv - lv) < eps) return i;
            pow *= 10;
        }
        return lim;
    }


    public static double calculate_dxor(double dec, int l) {
        double pow = quick_pow2(-l);
        return dec - pow * Math.floor(dec / pow);
    }

    public static int calculate_max(int l) {
        return cost[l];
    }

    public static long truncate(double value) {
        if (value > eps) return (long) Math.floor(value);
        if (value < -eps) return (long) Math.ceil(value);
        return 0;
    }
}
