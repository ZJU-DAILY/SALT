package SALT;

import algorithms.SALTSQL.SALTSQLRawCodec;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class SALTSQL {

    // 常量
    private static final BigDecimal FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO = new BigDecimal("0.1");
    private static final int ZERO_BITS = 1;      // 是否为零位
    private static final int SIGN_BITS = 1;      // 符号位
    private static final int WIN_BITS = 7;       // 窗口大小编码位数，默认为7
    private static final int EXPONENT_BITS = 3;  // 指数变化编码位数
    private static final int ULP_BITS = 3;       // 精度变化编码位数
    // 新增：窗口长度与 Fenwick 树编码位数（可通过 main 的 args 覆盖）
    private static final int WIN_NUM_BITS_DEFAULT = WIN_BITS + 2;   // 每个 window 的长度编码位数
    private static final int NUM_FENWICK_BITS_DEFAULT = 32; // Fenwick 树节点值编码位数
    // 新增：每个 window 实际使用 bits 数量与其 Fenwick 树编码位数（可通过 main 的 args 覆盖）
    private static final int WIN_LEN_BITS_DEFAULT = WIN_BITS + 7;   // 每个 window 的 bits 数量编码位数
    private static final int LEN_FENWICK_BITS_DEFAULT = 32;    // bits Fenwick 树节点值编码位数（long 建议 <= 64）
    private static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

    static BigDecimal[] readFirstColumnAsBigDecimals(String filename) throws IOException {
        List<String> rawStrings = new ArrayList<>();

        // 第一步：只以字符串形式读取
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filename), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = stripBom(line);  // ⭐ 自动去掉开头的 BOM

                if (line.isEmpty()) {
                    rawStrings.add(null);
                    continue;
                }

                String first = line.split(",", -1)[0].trim();
                first = stripBom(first);  // ⭐ 再保险一下，防止 BOM 只在字段里

                if (first.startsWith("\"") && first.endsWith("\"") && first.length() >= 2) {
                    first = first.substring(1, first.length() - 1);
                }

                rawStrings.add(first.isEmpty() ? null : first);
            }

        }

        // 第二步：统一转换为 BigDecimal
        List<BigDecimal> decimals = new ArrayList<>(rawStrings.size());
        for (String s : rawStrings) {
            if (s == null) {
                decimals.add(null);
            } else {
                try {
                    decimals.add(new BigDecimal(s));
                } catch (NumberFormatException e) {
                    decimals.add(null);
                }
            }
        }

        return decimals.toArray(new BigDecimal[0]);
    }

    // 取得 IEEE754 双精度 无偏指数（与 Python get_ieee_exponent 一致：返回 unbiased）
    static int getIeeeExponent(BigDecimal x) {
        if (x == null || x.signum() == 0) {
            return -1023; // 对应 IEEE 754 零值指数
        }
        double d = x.doubleValue();
        return Math.getExponent(d); // Java 1.5+ 自带方法，相当于 doubleToRawLongBits 的指数
    }

    // 统计十进制小数位数（对应 Python 的 Decimal(...).normalize() 再看 exponent）
    static int getUlpPlaces(BigDecimal x) {
        if (x == null) return 0;
        BigDecimal stripped = x.stripTrailingZeros();
        int scale = stripped.scale();
        return Math.max(scale, 0);
    }

    // 计算需要保存的尾数位数（对应 Python 的 get_man_save）
    static int getManSave(BigDecimal x) {
        if (x == null) throw new IllegalArgumentException("Input cannot be null");
        BigDecimal ax = x.abs();

        // 小于 1 的情况
        if (ax.compareTo(BigDecimal.ONE) < 0) {
            int E = getIeeeExponent(ax);     // 无偏指数
            int ulp = getUlpPlaces(ax);      // 十进制小数位数
            double t = Math.floor(-ulp * LOG2_10);
            return (int) Math.round(E - t);  // t 为负数，所以等价于 E + |t|
        }
        // >= 1 的情况
        else {
            long intPart = ax.longValue();

            long absInt = Math.abs(intPart);
            if (intPart == Long.MIN_VALUE) {   // 极端保护
                absInt = Long.MAX_VALUE;
            }
            int intBits = (absInt != 0) ? (64 - Long.numberOfLeadingZeros(absInt)) : 1;
            int ulp = getUlpPlaces(ax);        // 十进制精度
            int extra = (int) Math.ceil(ulp * LOG2_10);
            return intBits + extra - 1;
        }
    }

    // “按原始精度相减”，并按 mode 决定保留小数位；对应 Python subtract_with_original_precision
    static BigDecimal subtractWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        // 去除多余的尾随 0

        // 获取两个数的有效小数位数（精度）
        int pa = Math.max(a.scale(), 0);
        int pb = Math.max(b.scale(), 0);

        // 取较大的小数位数，以保留更高精度
        int p = Math.max(pa, pb);

        // 执行减法并按原始精度舍入
        BigDecimal result = a.subtract(b)
                .setScale(p, RoundingMode.HALF_UP)
                .stripTrailingZeros();

        return result;
    }

    public static String removeExtension(String fileName) {
        if (fileName == null) return null;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) { // 确保不是以 . 开头的隐藏文件
            return fileName.substring(0, dotIndex);
        }
        return fileName;    // 没有后缀就原样返回
    }

    public static String toSignMagnitudeBinary(int value, int length) {
        int magLen = length - 1;           // 数值位长度
        long maxMagnitude = (1L << magLen) - 1;  // 最大可表示绝对值

        long absVal = Math.abs((long) value);    // 用 long 防止溢出

        // 超范围：输出原码 -0
        if (absVal > maxMagnitude) {
            StringBuilder nz = new StringBuilder(length);
            nz.append('1');                // 符号位 1
            for (int i = 0; i < magLen; i++) {
                nz.append('0');            // 数值位全 0
            }
            return nz.toString();
        }

        int magnitude = (int) absVal;
        String magBin = Integer.toBinaryString(magnitude);

        // 左侧补 0 到 magLen 位
        StringBuilder sb = new StringBuilder(magLen);
        for (int i = magBin.length(); i < magLen; i++) {
            sb.append('0');
        }
        sb.append(magBin);

        char signBit = (value < 0) ? '1' : '0';
        return signBit + sb.toString();
    }

    public static String getDoubleMantissaBits(double value, int numBits) {
        if (numBits == 0) {
            return "";
        }

        long bits = Double.doubleToRawLongBits(value);

        // 低 52 位是尾数（fraction）
        long mantissa = bits & ((1L << 52) - 1);

        StringBuilder sb = new StringBuilder(numBits);
        // 从 mantissa 的最高位（bit 51）往下取 numBits 位
        for (int i = 51; i >= 52 - numBits; i--) {
            long bit = (mantissa >>> i) & 1L;
            sb.append(bit == 0 ? '0' : '1');
        }

        return sb.toString();
    }

    private static String stripBom(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        // 如果第一个字符是 BOM（\uFEFF），去掉它
        if (s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        // 有些情况下中间也可能混进 BOM，这里保险起见全替换掉
        return s.replace("\uFEFF", "");
    }

    private static String toFixedBinary(int value, int length) {
        String s = Integer.toBinaryString(value);
        if (s.length() > length) {
            throw new IllegalArgumentException(
                    "value " + value + " 超过长度 " + length + " 能表示的范围");
        }
        return String.format("%" + length + "s", s).replace(' ', '0');
    }

    // ==================== 新增：把整个数组当成一个 window，压缩为 01String ====================
    // 规则与 main 中 windowStart / delta 的编码保持一致
    public static String encodeAsOneWindow01String(List<BigDecimal> datas, int exponentBits, int ulpBits) {
        if (datas == null || datas.isEmpty()) return "";
        StringBuilder bits = new StringBuilder();
        BigDecimal reference = null;
        boolean first = true;
        for (BigDecimal value : datas) {
            if (value == null) continue;
            double v = value.doubleValue();
            if (!Double.isFinite(v)) throw new IllegalArgumentException("SALT+ BigDecimal API requires finite binary64 values");
            bits.append(SALTSQLRawCodec.encode(v, reference, first, exponentBits, ulpBits));
            if (first) reference = SALTSQLRawCodec.reference(v);
            first = false;
        }
        return bits.toString();
    }


    public static final class BitOutputStream implements Closeable {
        private final OutputStream out;
        private int currentByte = 0;
        private int numBitsFilled = 0;

        public BitOutputStream(OutputStream out) {
            this.out = out;
        }

        public void writeBit(int bit) throws IOException {
            currentByte = (currentByte << 1) | (bit & 1);
            numBitsFilled++;
            if (numBitsFilled == 8) {
                out.write(currentByte);
                numBitsFilled = 0;
                currentByte = 0;
            }
        }

        public void writeBits(long value, int nBits) throws IOException {
            for (int i = nBits - 1; i >= 0; i--) {
                writeBit((int)((value >>> i) & 1L));
            }
        }

        public void writeBitString(String bits01) throws IOException {
            for (int i = 0; i < bits01.length(); i++) {
                char c = bits01.charAt(i);
                writeBit(c == '1' ? 1 : 0);
            }
        }

        /** 若需要在某些字段后对齐到整字节，可调用它 */
        public void alignToByte() throws IOException {
            if (numBitsFilled > 0) {
                currentByte <<= (8 - numBitsFilled);
                out.write(currentByte);
                numBitsFilled = 0;
                currentByte = 0;
            }
        }

        @Override
        public void close() throws IOException {
            alignToByte();
            out.close();
        }
    }

    private static void writeWindowLengthsFile(String fileName, List<Integer> windowLens, int winLenBits) throws IOException {
        try (BitOutputStream out = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {
            // meta：winLenBits(8bit) + windowCount(32bit)  ——以前是一行文本，现在是 40 个 bit
            out.writeBits(winLenBits, 8);
            out.writeBits(windowLens.size(), 32);

            // body：每个 len 用 winLenBits 位写入（紧密打包，无换行）
            for (int len : windowLens) {
                out.writeBits(len, winLenBits);
            }
            // close() 里会 alignToByte，这里可不写
        }
    }

    private static long[] buildFenwickFromLengths(List<Integer> windowLens) {
        int n = windowLens.size();
        long[] bit = new long[n + 1]; // 1-indexed
        for (int i = 1; i <= n; i++) {
            long delta = windowLens.get(i - 1);
            for (int j = i; j <= n; j += (j & -j)) {
                bit[j] += delta;
            }
        }
        return bit;
    }

    private static void writeFenwickFile(String fileName, List<Integer> windowLens, int fenwickBits) throws IOException {
        long[] bit = buildFenwickFromLengths(windowLens);
        int n = windowLens.size();

        try (BitOutputStream out = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {
            // meta：fenwickBits(8bit) + windowCount(32bit)
            out.writeBits(fenwickBits, 8);
            out.writeBits(n, 32);

            // body：Fenwick 树每个节点用 fenwickBits 位写入
            for (int i = 1; i <= n; i++) {
                out.writeBits(bit[i], fenwickBits);
            }
        }
    }

    // 额外输出：每个 window 实际使用 bits 数量文件
    private static void writeWindowBitsUsedFile(String fileName, List<Long> windowBitCounts, int winBitsBits) throws IOException {
        try (BitOutputStream out = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {
            // meta：winBitsBits(8bit) + windowCount(32bit)
            out.writeBits(winBitsBits, 8);
            out.writeBits(windowBitCounts.size(), 32);

            // body：每个 bits 用 winBitsBits 位写入
            for (long bits : windowBitCounts) {
                out.writeBits(bits, winBitsBits);
            }
        }
    }

    // 根据 window bits 数量构建 Fenwick 树（1-indexed）
    private static long[] buildFenwickFromLongs(List<Long> values) {
        int n = values.size();
        long[] bit = new long[n + 1]; // 1-indexed
        for (int i = 1; i <= n; i++) {
            long delta = values.get(i - 1);
            for (int j = i; j <= n; j += (j & -j)) {
                bit[j] += delta;
            }
        }
        return bit;
    }

    // 额外输出：基于 window bits 数量的 Fenwick 树文件
    private static void writeFenwickLongFile(String fileName, List<Long> values, int fenwickBits) throws IOException {
        long[] bit = buildFenwickFromLongs(values);
        int n = values.size();

        try (BitOutputStream out = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {
            // meta：fenwickBits(8bit) + windowCount(32bit)
            out.writeBits(fenwickBits, 8);
            out.writeBits(n, 32);

            // body：每个节点用 fenwickBits 位写入
            for (int i = 1; i <= n; i++) {
                out.writeBits(bit[i], fenwickBits);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("datasets/forCRUD");
        if (!dir.isDirectory()) {
            System.err.println("dataset/ 目录不存在。请将 CSV 文件放在 dataset/ 下。");
            return;
        }

        // args[0]: windowLenBits, args[1]: fenwickBits, args[2]: windowBitsBits, args[3]: bitsFenwickBits（均可选）
        int winLenBits = WIN_NUM_BITS_DEFAULT;
        int fenwickBits = NUM_FENWICK_BITS_DEFAULT;
        int winBitsBits = WIN_LEN_BITS_DEFAULT;
        int bitsFenwickBits = LEN_FENWICK_BITS_DEFAULT;
        if (args != null) {
            if (args.length >= 1 && !args[0].trim().isEmpty()) {
                winLenBits = Integer.parseInt(args[0].trim());
            }
            if (args.length >= 2 && !args[1].trim().isEmpty()) {
                fenwickBits = Integer.parseInt(args[1].trim());
            }
            if (args.length >= 3 && !args[2].trim().isEmpty()) {
                winBitsBits = Integer.parseInt(args[2].trim());
            }
            if (args.length >= 4 && !args[3].trim().isEmpty()) {
                bitsFenwickBits = Integer.parseInt(args[3].trim());
            }
        }

        // 输出结果 CSV
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("results_SQL.csv"), StandardCharsets.UTF_8))) {

            out.println("Filename,Compression Bits,time,Average Index Bits"); // CSV header

            int winCapacity = 1 << WIN_BITS; // 窗口大小

            // 遍历 dataset 下的所有文件
            List<File> files = Arrays.stream(Objects.requireNonNull(dir.listFiles()))
                    .filter(File::isFile)
                    .sorted(Comparator.comparing(File::getName))
                    .collect(Collectors.toList());

            for (File f : files) {
                // 为当前文件创建一个 bit 输出文件
                String txtFileName = removeExtension(f.getName()) + ".txt";
                try (PrintWriter txtOut = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(txtFileName), StandardCharsets.UTF_8))) {

                    String meta = "0000" + toFixedBinary(SALTSQLRawCodec.VERSION, 4) + toFixedBinary(WIN_BITS, 4)
                            + toFixedBinary(EXPONENT_BITS, 4)
                            + toFixedBinary(ULP_BITS, 4);
                    txtOut.println(meta);  // txt 第一行就是参数信息

                    // 读取 CSV 第一列为 BigDecimal
                    BigDecimal[] datas = readFirstColumnAsBigDecimals(f.getPath());
                    double startTime = System.nanoTime();
                    double sumBits = 0.0;
                    double bitsForFenwickNum = 0.0;
                    double bitsForFenwickLen = 0.0;
                    double bitsForNum = 0.0;
                    double bitsForLen = 0.0;

                    int blockCount = 0;
                    int winSize = 1<<WIN_BITS;
                    BigDecimal firstValue = BigDecimal.ZERO;
                    List<Integer> windowLens = new ArrayList<>(); // 记录每个 window 实际长度（按非 null 计数）
                    List<Long> windowBitCounts = new ArrayList<>(); // 记录每个 window 实际使用 bits 数量
                    for (int idx = 0; idx < datas.length; idx++) {

                        BigDecimal value = datas[idx];
                        if (value == null) {
                            continue;
                        }

                        value = value.stripTrailingZeros();
                        String str="";
                        boolean isWindowStart = (blockCount % winSize == 0);
                        if (isWindowStart) {
                            windowLens.add(0);
                            windowBitCounts.add(0L);
                        }
                        // 当前元素属于最后一个 window
                        int wIdx = windowLens.size() - 1;
                        windowLens.set(wIdx, windowLens.get(wIdx) + 1);

                        double inputValue = value.doubleValue();
                        if (!Double.isFinite(inputValue)) throw new IllegalArgumentException("Non-finite CSV value");
                        str = SALTSQLRawCodec.encode(inputValue, firstValue, isWindowStart, EXPONENT_BITS, ULP_BITS);
                        if (isWindowStart) firstValue = SALTSQLRawCodec.reference(inputValue);

                        if(blockCount % winSize == winSize - 1 || idx == datas.length - 1){
                            sumBits = sumBits + WIN_NUM_BITS_DEFAULT;
                            sumBits = sumBits + NUM_FENWICK_BITS_DEFAULT;
                            sumBits = sumBits + LEN_FENWICK_BITS_DEFAULT;
                            sumBits = sumBits + WIN_LEN_BITS_DEFAULT;
                            bitsForFenwickNum += NUM_FENWICK_BITS_DEFAULT;
                            bitsForFenwickLen += LEN_FENWICK_BITS_DEFAULT;
                            bitsForNum += WIN_NUM_BITS_DEFAULT;
                            bitsForLen += WIN_LEN_BITS_DEFAULT;
                        }

                        sumBits += str.length();
                        windowBitCounts.set(wIdx, windowBitCounts.get(wIdx) + str.length());
                        txtOut.println(str);
                        blockCount++;
                    }

                    // 额外输出：window 长度文件 与 Fenwick 树文件
                    String baseName = removeExtension(f.getName());
                    String winLenFileName = baseName + "_window_num.bin";
                    String fenwickFileName = baseName + "_num_fenwick.bin";
                    String winBitsFileName = baseName + "_window_len.bin";
                    String bitsFenwickFileName = baseName + "_len_fenwick.bin";
                    writeWindowLengthsFile(winLenFileName, windowLens, winLenBits);
                    writeFenwickFile(fenwickFileName, windowLens, fenwickBits);
                    writeWindowBitsUsedFile(winBitsFileName, windowBitCounts, winBitsBits);
                    writeFenwickLongFile(bitsFenwickFileName, windowBitCounts, bitsFenwickBits);
                    double ratio = sumBits / (1.0 * Math.max(blockCount, 1));
                    double endTime = System.nanoTime();
                    System.out.println("耗时(ms): " + (endTime - startTime) / 1000000);
                    System.out.printf("%s: %f%n", f.getName(), ratio);
                    out.printf("%s,%f,%f,%f%n", f.getName(), ratio, (endTime - startTime) / 1000000 , (bitsForLen+bitsForNum+bitsForFenwickLen+bitsForFenwickNum)/ (1.0 * Math.max(blockCount, 1)) );

                } // try-with-resources 结束时会自动关闭 txtOut
                String bitFileName = removeExtension(f.getName()) + ".bin";
                try (BitOutputStream bitOut = new BitOutputStream(
                        new BufferedOutputStream(new FileOutputStream(bitFileName)))) {

                    String meta = "0000" + toFixedBinary(SALTSQLRawCodec.VERSION, 4) + toFixedBinary(WIN_BITS, 4)
                            + toFixedBinary(EXPONENT_BITS, 4)
                            + toFixedBinary(ULP_BITS, 4);
                    bitOut.writeBitString(meta);  // txt 第一行就是参数信息

                    // 读取 CSV 第一列为 BigDecimal
                    BigDecimal[] datas = readFirstColumnAsBigDecimals(f.getPath());
                    double startTime = System.nanoTime();
                    double sumBits = 0.0;
                    double bitsForFenwickNum = 0.0;
                    double bitsForFenwickLen = 0.0;
                    double bitsForNum = 0.0;
                    double bitsForLen = 0.0;

                    int blockCount = 0;
                    int winSize = 1<<WIN_BITS;
                    BigDecimal firstValue = BigDecimal.ZERO;
                    List<Integer> windowLens = new ArrayList<>(); // 记录每个 window 实际长度（按非 null 计数）
                    List<Long> windowBitCounts = new ArrayList<>(); // 记录每个 window 实际使用 bits 数量
                    for (int idx = 0; idx < datas.length; idx++) {

                        BigDecimal value = datas[idx];
                        if (value == null) {
                            continue;
                        }

                        value = value.stripTrailingZeros();
                        String str="";
                        boolean isWindowStart = (blockCount % winSize == 0);
                        if (isWindowStart) {
                            windowLens.add(0);
                            windowBitCounts.add(0L);
                        }
                        // 当前元素属于最后一个 window
                        int wIdx = windowLens.size() - 1;
                        windowLens.set(wIdx, windowLens.get(wIdx) + 1);

                        double inputValue = value.doubleValue();
                        if (!Double.isFinite(inputValue)) throw new IllegalArgumentException("Non-finite CSV value");
                        str = SALTSQLRawCodec.encode(inputValue, firstValue, isWindowStart, EXPONENT_BITS, ULP_BITS);
                        if (isWindowStart) firstValue = SALTSQLRawCodec.reference(inputValue);

                        if(blockCount % winSize == winSize - 1 || idx == datas.length - 1){

                            sumBits = sumBits + WIN_NUM_BITS_DEFAULT;
                            sumBits = sumBits + NUM_FENWICK_BITS_DEFAULT;
                            sumBits = sumBits + LEN_FENWICK_BITS_DEFAULT;
                            sumBits = sumBits + WIN_LEN_BITS_DEFAULT;
                        }

                        sumBits += str.length();
                        windowBitCounts.set(wIdx, windowBitCounts.get(wIdx) + str.length());
                        bitOut.writeBitString(str);
                        blockCount++;
                    }

                } // try-with-resources 结束时会自动关闭 binOut
            }

        }
    }

}

