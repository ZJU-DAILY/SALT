package utils;

public class BinaryTools {
    public static long xor(double a, double b) {
        return Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b);
    }

    public static double xor(long a, double b) {
        return Double.longBitsToDouble(a ^ Double.doubleToRawLongBits(b));
    }

    public static int leadZeros(long v, int size) {
        long p = 1L << (size - 1);
        for (int i = 0; i < size; i++) {
            if ((v & p) != 0) return i;
            p >>>= 1;
        }
        return size;
    }

    public static int tailZeros(long v, int size) {
        long p = 1;
        for (int i = 0; i < size; i++) {
            if ((v & p) != 0) return i;
            p <<= 1;
        }
        return size;
    }

    public static int CBL(long v,int size){
        int lead = leadZeros(v,size);
        int tail = tailZeros(v,size);
        int CBL = size - lead;
        if(CBL > 0)CBL -= tail;
        return CBL;
    }
}
