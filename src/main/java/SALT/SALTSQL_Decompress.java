package SALT;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Decompressor for files encoded by Our_SQL.java.
 *
 * File format:
 *  - First line: 12 bits header = WIN_BITS(4) + EXPONENT_BITS(4) + ULP_BITS(4)
 *  - Each subsequent line: one record bitstring
 *
 * Block format (winSize = 1 << WIN_BITS):
 *  A) First record in each block (idx % winSize == 0):
 *     - zeroFlag(1): '1' => value = 0 ; '0' => non-zero value
 *     - if non-zero: sign(1) + ulpPlaces(4, unsigned) + exponent(11, sign-magnitude) + mantissaBits(rest)
 *
 *  B) Other records in block:
 *     - zeroFlag(1): '1' => delta = 0 (value == firstValue) ; '0' => non-zero delta
 *     - if non-zero:
 *         sign(1)
 *         ulpDelta(sign-magnitude, ULP_BITS). If it is "-0" overflow marker, read ulpPlaces(4, unsigned) next.
 *         expFlag(1): '0' => exponent uses EXPONENT_BITS ; '1' => exponent uses 11
 *         exponent(sign-magnitude, EXPONENT_BITS or 11)
 *         mantissaBits(rest)
 *
 * The sign-magnitude "-0" (sign bit 1 and all-zero magnitude) is used as overflow marker,
 * same as OurDecompress.
 */
public class SALTSQL_Decompress {

    private static final BigDecimal FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO = new BigDecimal("0.1");
    // Same as encoder: sign-magnitude "-0" is treated as overflow marker -> return null.
    public static Integer fromSignMagnitudeBinary(String bits) {
        if (bits == null || bits.isEmpty()) return null;
        boolean negative = (bits.charAt(0) == '1');
        String magStr = bits.substring(1);
        int magnitude = Integer.parseInt(magStr, 2);
        if (negative && magnitude == 0) return null;
        return negative ? -magnitude : magnitude;
    }

    static int getUlpPlaces(BigDecimal x) {
        if (x == null) return 0;
        BigDecimal stripped = x.stripTrailingZeros();
        int scale = stripped.scale();
        return Math.max(scale, 0);
    }
    /**
     * Rebuild a double from (sign, unbiased exponent, mantissaBits, ulpPlaces),
     * aligned with OurDecompress.reconstruct().
     */
    public static double reconstruct(int sign, int exponent, String mantissa, int ulp) {
        int biasedExp = exponent + 1023;
        if (biasedExp < 0) biasedExp = 0;
        if (biasedExp > 0x7FF) biasedExp = 0x7FF;

        String mantissaFull;
        if (mantissa.length() >= 52) {
            mantissaFull = mantissa.substring(0, 52);
        } else {
            StringBuilder sb = new StringBuilder(52);
            sb.append(mantissa);
            while (sb.length() < 52) sb.append('0');
            mantissaFull = sb.toString();
        }

        long mantissaBits = mantissaFull.isEmpty() ? 0L : Long.parseLong(mantissaFull, 2);

        long bits = ((long) sign & 1L) << 63;
        bits |= ((long) biasedExp & 0x7FFL) << 52;
        bits |= (mantissaBits & ((1L << 52) - 1));

        double val = Double.longBitsToDouble(bits);

        BigDecimal d = new BigDecimal(Double.toString(val));
        int scale = Math.max(0, ulp);

        BigDecimal result;
        if (val >= 0.0) {
            result = d.setScale(scale, RoundingMode.UP);
        } else {
            BigDecimal q = d.negate().setScale(scale, RoundingMode.UP);
            result = q.negate();
        }
        return result.doubleValue();
    }

    /**
     * Rebuilds a value from an already aligned 52-bit mantissa. This is the
     * allocation-free counterpart of {@link #reconstruct(int, int, String, int)}
     * used by indexed aggregation.
     */
    private static double reconstructFromMantissaBits(int sign, int exponent,
                                                       long mantissaBits, int ulp) {
        int biasedExp = exponent + 1023;
        if (biasedExp < 0) biasedExp = 0;
        if (biasedExp > 0x7FF) biasedExp = 0x7FF;

        long bits = ((long) sign & 1L) << 63;
        bits |= ((long) biasedExp & 0x7FFL) << 52;
        bits |= mantissaBits & ((1L << 52) - 1L);

        double val = Double.longBitsToDouble(bits);
        BigDecimal decimal = new BigDecimal(Double.toString(val));
        int scale = Math.max(0, ulp);
        BigDecimal result;
        if (val >= 0.0) {
            result = decimal.setScale(scale, RoundingMode.UP);
        } else {
            result = decimal.negate().setScale(scale, RoundingMode.UP).negate();
        }
        return result.doubleValue();
    }

    // ================= NEW: Decompress .bin bitstream (no record length) =================
    public static void runDecompressBinStream() throws Exception {
        File dir = new File(".");
        List<File> files = Arrays.stream(Objects.requireNonNull(dir.listFiles()))
                .filter(f -> {
                    if (!f.isFile()) return false;
                    String name = f.getName();
                    if (!name.endsWith(".bin")) return false;

                    // 去掉 ".bin" 后判断是否是 sidecar
                    String base = name.substring(0, name.length() - 4);
                    return !(base.endsWith("_len_fenwick")
                            || base.endsWith("_num_fenwick")
                            || base.endsWith("_window_len")
                            || base.endsWith("_window_num"));
                })
                .sorted(Comparator.comparing(File::getName))
                .collect(Collectors.toList());

        if (files.isEmpty()) {
            System.err.println("No .bin encoded files found in current directory.");
            return;
        }

        for (File f : files) {
            String baseName = f.getName();
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            String decodedName = baseName + ".csv";

            long t0 = System.nanoTime();
            System.out.print("Decompress(BIN-stream): " + f.getName() + " -> " + decodedName);

            decompressBinStreamFile(f, new File(decodedName));

            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf(" | %.3f ms%n", ms);
        }
        System.out.println("Done.");
    }

    public static void decompressBinStreamFile(File binFile, File outCsv) throws Exception {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(binFile));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outCsv), StandardCharsets.UTF_8))) {

            BitInput in = new BitInput(fis);

            // --- meta: 12 bits = WIN_BITS(4) + EXPONENT_BITS(4) + ULP_BITS(4) ---
            Integer winBitsI = in.readBitsAsIntOrNull(4);
            Integer expBitsI = in.readBitsAsIntOrNull(4);
            Integer ulpBitsI = in.readBitsAsIntOrNull(4);
            if (winBitsI == null || expBitsI == null || ulpBitsI == null) {
                System.err.println("Empty/invalid .bin (missing 12-bit meta): " + binFile.getName());
                return;
            }
            int WIN_BITS = winBitsI;
            int EXPONENT_BITS = expBitsI;
            int ULP_BITS = ulpBitsI;

            int[] windowLens = tryLoadWindowLengths(binFile);

            BigDecimal firstValue = BigDecimal.ZERO;
            long idxInStream = 0;

            if (windowLens == null) {
                int winSize = 1 << WIN_BITS;

                while (true) {
                    boolean isBlockFirst = (idxInStream % winSize == 0);

                    Integer zeroFlag = in.readBitsAsIntOrNull(1);
                    if (zeroFlag == null) break; // clean EOF

                    BigDecimal value;

                    if (isBlockFirst) {
                        if (zeroFlag == 1) {
                            value = BigDecimal.ZERO;
                            // 窗口首条为 0：解码值仍为 0，但为了与压缩端一致，将 firstValue 固定为 0.1 作为后续 delta 基准
                            firstValue = FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO;
                        } else {
                            Integer signBit = in.readBitsAsIntOrNull(1);
                            Integer ulpPlaces = in.readBitsAsIntOrNull(4);
                            Integer expUnbiased = readSignMagnitudeOrNull(in, 11);
                            if (signBit == null || ulpPlaces == null || expUnbiased == null) break;

                            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
                            String mantissaBits = in.readBitsAs01StringOrNull(manSave);
                            if (mantissaBits == null) break;

                            double v = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
                            value = BigDecimal.valueOf(v).stripTrailingZeros();
                            firstValue = value;
                        }
                    } else {
                        if (zeroFlag == 1) {
                            value = firstValue;
                        } else {
                            Integer signBit = in.readBitsAsIntOrNull(1);
                            Integer ulpDelta = readSignMagnitudeOrNull(in, ULP_BITS); // null 表示 overflow marker "-0"
                            if (signBit == null) break;

                            int ulpPlaces;
                            if (ulpDelta == null) {
                                Integer ulpAbs = in.readBitsAsIntOrNull(4); // overflow 时写入绝对 ulpPlaces(4 bits)
                                if (ulpAbs == null) break;
                                ulpPlaces = ulpAbs;
                            } else {
                                ulpPlaces = getUlpPlaces(firstValue) + ulpDelta; // 与 txt 解码一致
                            }

                            Integer expFlag = in.readBitsAsIntOrNull(1);
                            if (expFlag == null) break;
                            int expLen = (expFlag == 0) ? EXPONENT_BITS : 11;

                            Integer expUnbiased = readSignMagnitudeOrNull(in, expLen);
                            if (expUnbiased == null) break;

                            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
                            String mantissaBits = in.readBitsAs01StringOrNull(manSave);
                            if (mantissaBits == null) break;

                            double deltaD = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
                            BigDecimal delta = BigDecimal.valueOf(deltaD);

                            value = firstValue.add(delta).stripTrailingZeros();
                        }
                    }

                    out.println(value.toPlainString());
                    idxInStream++;
                }
            } else {
                boolean reachedEof = false;

                for (int w = 0; w < windowLens.length; w++) {
                    int recordsInWindow = windowLens[w];
                    if (recordsInWindow <= 0) continue;

                    for (int r = 0; r < recordsInWindow; r++) {
                        boolean isBlockFirst = (r == 0);

                        Integer zeroFlag = in.readBitsAsIntOrNull(1);
                        if (zeroFlag == null) { reachedEof = true; break; } // clean EOF

                        BigDecimal value;

                        if (isBlockFirst) {
                            if (zeroFlag == 1) {
                                value = BigDecimal.ZERO;
                                // 窗口首条为 0：解码值仍为 0，但为了与压缩端一致，将 firstValue 固定为 0.1 作为后续 delta 基准
                                firstValue = FIRST_VALUE_WHEN_WINDOW_START_IS_ZERO;
                            } else {
                                Integer signBit = in.readBitsAsIntOrNull(1);
                                Integer ulpPlaces = in.readBitsAsIntOrNull(4);
                                Integer expUnbiased = readSignMagnitudeOrNull(in, 11);
                                if (signBit == null || ulpPlaces == null || expUnbiased == null) { reachedEof = true; break; }

                                int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
                                String mantissaBits = in.readBitsAs01StringOrNull(manSave);
                                if (mantissaBits == null) { reachedEof = true; break; }

                                double v = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
                                value = BigDecimal.valueOf(v).stripTrailingZeros();
                                firstValue = value;
                            }
                        } else {
                            if (zeroFlag == 1) {
                                value = firstValue;
                            } else {
                                Integer signBit = in.readBitsAsIntOrNull(1);
                                Integer ulpDelta = readSignMagnitudeOrNull(in, ULP_BITS);
                                if (signBit == null) { reachedEof = true; break; }

                                int ulpPlaces;
                                if (ulpDelta == null) {
                                    Integer ulpAbs = in.readBitsAsIntOrNull(4);
                                    if (ulpAbs == null) { reachedEof = true; break; }
                                    ulpPlaces = ulpAbs;
                                } else {
                                    ulpPlaces = getUlpPlaces(firstValue) + ulpDelta;
                                }

                                Integer expFlag = in.readBitsAsIntOrNull(1);
                                if (expFlag == null) { reachedEof = true; break; }
                                int expLen = (expFlag == 0) ? EXPONENT_BITS : 11;

                                Integer expUnbiased = readSignMagnitudeOrNull(in, expLen);
                                if (expUnbiased == null) { reachedEof = true; break; }

                                int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
                                String mantissaBits = in.readBitsAs01StringOrNull(manSave);
                                if (mantissaBits == null) { reachedEof = true; break; }

                                double deltaD = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
                                BigDecimal delta = BigDecimal.valueOf(deltaD);

                                value = firstValue.add(delta).stripTrailingZeros();
                            }
                        }

                        out.println(value.toPlainString());
                        idxInStream++;
                    }

                    if (reachedEof) break;
                }

                if (reachedEof) {
                    System.err.println("EOF reached before consuming all windows described in *_window_num.bin for "
                            + binFile.getName() + ". Decoded records=" + idxInStream + ", described windows=" + windowLens.length);
                } }

        }
    }

    /**
     * 对齐 encoder 的 getManSave(BigDecimal x) 公式，但不依赖 x 本身：
     * - 小于 1：只需 exponent(无偏) 与 ulpPlaces
     * - 大于等于 1：intBits 在 double 语义下等价于 exponent+1（假设值在 long 可表示范围内时与 encoder 一致）
     * 公式来源：Our_SQL.getManSave()
     */
    private static int calcManSaveFromExpAndUlp(int exponentUnbiased, int ulpPlaces) {
        final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

        if (exponentUnbiased < 0) { // |x| < 1（非零）
            double t = Math.floor(-ulpPlaces * LOG2_10);
            return (int) Math.round(exponentUnbiased - t);
        } else { // |x| >= 1
            int intBits = Math.max(1, exponentUnbiased + 1);
            int extra = (int) Math.ceil(ulpPlaces * LOG2_10);
            return intBits + extra - 1;
        }
    }

    /** 读取 sign-magnitude：返回 Integer；若读到 "-0"(sign=1 且 magnitude=0) 作为 overflow marker，则返回 null。 */
    private static Integer readSignMagnitudeOrNull(BitInput in, int length) throws IOException {
        Integer sign = in.readBitsAsIntOrNull(1);
        if (sign == null) return null;
        Integer mag = in.readBitsAsIntOrNull(length - 1);
        if (mag == null) return null;
        if (sign == 1 && mag == 0) return null; // overflow marker "-0"
        return (sign == 1) ? -mag : mag;
    }

    /** 最小 bit 输入：顺序读取，不要求 byte 对齐；EOF 时返回 null 方便“自然结束”。 */
    private static final class BitInput {
        private final InputStream in;
        private int curByte = 0;
        private int bitPos = 8; // 0..7 used; 8 means need a new byte

        BitInput(InputStream in) { this.in = in; }

        private int readBit() throws IOException {
            if (bitPos >= 8) {
                curByte = in.read();
                if (curByte < 0) return -1;
                bitPos = 0;
            }
            int bit = (curByte >> (7 - bitPos)) & 1;
            bitPos++;
            return bit;
        }

        Integer readBitsAsIntOrNull(int n) throws IOException {
            int val = 0;
            for (int i = 0; i < n; i++) {
                int b = readBit();
                if (b < 0) return null;
                val = (val << 1) | b;
            }
            return val;
        }

        String readBitsAs01StringOrNull(int n) throws IOException {
            if (n <= 0) return "";
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                int b = readBit();
                if (b < 0) return null;
                sb.append(b == 0 ? '0' : '1');
            }
            return sb.toString();
        }
    }

    /**
     * Try to locate and load the per-window record counts from a sibling "*_window_num.bin".
     * If not found or parsing fails, returns null and caller may fall back to fixed window size logic.
     *
     * window_num.bin format:
     *  - first 40 bits meta:
     *      * first 8 bits: LEN_BITS (how many bits are used to store each window's record count)
     *      * next 32 bits: WINDOW_COUNT (how many windows)
     *  - then WINDOW_COUNT integers, each stored in LEN_BITS bits: record count for that window
     */
    private static int[] tryLoadWindowLengths(File binFile) {
        try {
            File parent = binFile.getParentFile();
            String name = binFile.getName();
            if (name.endsWith("_window_num.bin")) return null; // not a data bin
            String base = name.endsWith(".bin") ? name.substring(0, name.length() - 4) : name;
            File windowNumFile = new File(parent, base + "_window_num.bin");
            if (!windowNumFile.exists()) return null;
            return readWindowLengths(windowNumFile);
        } catch (Exception e) {
            System.err.println("Failed to load *_window_num.bin for " + binFile.getName()
                    + ", fallback to fixed window size. Reason: " + e.getMessage());
            return null;
        }
    }

    private static int[] readWindowLengths(File windowNumFile) throws IOException {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(windowNumFile))) {
            BitInput in = new BitInput(fis);

            Integer lenBitsI = in.readBitsAsIntOrNull(8);
            Integer winCountI = in.readBitsAsIntOrNull(32);
            if (lenBitsI == null || winCountI == null) {
                throw new EOFException("window_num.bin missing 40-bit meta header");
            }
            int LEN_BITS = lenBitsI;
            long WINDOW_COUNT_L = Integer.toUnsignedLong(winCountI);
            if (WINDOW_COUNT_L > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too many windows: " + WINDOW_COUNT_L);
            }
            int WINDOW_COUNT = (int) WINDOW_COUNT_L;
            if (LEN_BITS <= 0 || LEN_BITS > 31) {
                throw new IllegalArgumentException("Invalid LEN_BITS: " + LEN_BITS);
            }

            int[] lens = new int[WINDOW_COUNT];
            for (int i = 0; i < WINDOW_COUNT; i++) {
                Integer v = in.readBitsAsIntOrNull(LEN_BITS);
                if (v == null) {
                    throw new EOFException("window_num.bin ended early at window " + i);
                }
                lens[i] = v;
            }
            return lens;
        }
    }

    public static List<BigDecimal> decompressOneWindow01Stream(String bitStream01,
                                                               int EXPONENT_BITS,
                                                               int ULP_BITS) throws IOException {
        if (bitStream01 == null) return Collections.emptyList();
        String s = bitStream01.trim();
        if (s.isEmpty()) return Collections.emptyList();
        if (!s.matches("[01]+")) {
            throw new IllegalArgumentException("bitStream01 must contain only '0'/'1'.");
        }
        if (EXPONENT_BITS <= 0 || EXPONENT_BITS > 11) {
            throw new IllegalArgumentException("EXPONENT_BITS must be in (0, 11].");
        }
        if (ULP_BITS <= 0 || ULP_BITS > 11) {
            throw new IllegalArgumentException("ULP_BITS must be in (0, 11].");
        }

        BitStringInput in = new BitStringInput(s);
        List<BigDecimal> out = new ArrayList<>();

        // ---- record #0: window firstValue record ----
        Integer zeroFlag0 = in.readBitsAsIntOrNull(1);
        if (zeroFlag0 == null) return out;

        BigDecimal firstValue = BigDecimal.ZERO;

        if (zeroFlag0 == 1) {
            // window 首条为 0
            out.add(BigDecimal.ZERO);
            // firstValue 仍保持为 0，后续 delta 都以它为基准
        } else {
            Integer signBit = in.readBitsAsIntOrNull(1);
            Integer ulpPlaces = in.readBitsAsIntOrNull(4);
            Integer expUnbiased = readSignMagnitudeOrNull(in, 11); // window 首条 exponent 固定 11 bits
            if (signBit == null || ulpPlaces == null || expUnbiased == null) return out;

            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
            String mantissaBits = in.readBitsAs01StringOrNull(manSave);
            if (mantissaBits == null) return out;

            double v = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
            firstValue = BigDecimal.valueOf(v).stripTrailingZeros();
            out.add(firstValue);
        }

        // ---- record #1..N: read until stream ends ----
        while (in.hasRemaining()) {
            Integer zeroFlag = in.readBitsAsIntOrNull(1);
            if (zeroFlag == null) break;

            if (zeroFlag == 1) {
                out.add(firstValue);
                continue;
            }

            Integer signBit = in.readBitsAsIntOrNull(1);
            if (signBit == null) break;

            Integer ulpDelta = readSignMagnitudeOrNull(in, ULP_BITS); // null => overflow marker "-0"
            int ulpPlaces;
            if (ulpDelta == null) {
                Integer ulpAbs = in.readBitsAsIntOrNull(4);
                if (ulpAbs == null) break;
                ulpPlaces = ulpAbs;
            } else {
                ulpPlaces = getUlpPlaces(firstValue) + ulpDelta;
            }

            Integer expFlag = in.readBitsAsIntOrNull(1);
            if (expFlag == null) break;
            int expLen = (expFlag == 0) ? EXPONENT_BITS : 11;

            Integer expUnbiased = readSignMagnitudeOrNull(in, expLen);
            if (expUnbiased == null) break;

            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
            String mantissaBits = in.readBitsAs01StringOrNull(manSave);
            if (mantissaBits == null) break;

            double deltaD = reconstruct(signBit, expUnbiased, mantissaBits, ulpPlaces);
            BigDecimal delta = BigDecimal.valueOf(deltaD);

            BigDecimal value = firstValue.add(delta).stripTrailingZeros();
            out.add(value);
        }

        return out;
    }

    /** Result of aggregating a slice while decoding one compressed window. */
    public static final class WindowAggregateResult {
        public final int decodedCount;
        public final int selectedCount;
        public final double aggregate;

        private WindowAggregateResult(int decodedCount, int selectedCount, double aggregate) {
            this.decodedCount = decodedCount;
            this.selectedCount = selectedCount;
            this.aggregate = aggregate;
        }
    }

    /**
     * Decodes one window and aggregates only {@code [fromInclusive, toExclusive)}.
     * All records are parsed so corrupt/truncated windows are still detected, but
     * no list, per-record mantissa string, or unselected BigDecimal is created.
     * Operation codes are 0=SUM, 1=AVG's sum, 2=MIN, and 3=MAX.
     */
    public static WindowAggregateResult aggregateOneWindow01Stream(
            String bitStream01, int EXPONENT_BITS, int ULP_BITS,
            int fromInclusive, int toExclusive, int operation) throws IOException {
        if (fromInclusive < 0 || toExclusive < fromInclusive) {
            throw new IllegalArgumentException("Invalid window slice: [" + fromInclusive
                    + ", " + toExclusive + ")");
        }
        if (operation < 0 || operation > 3) {
            throw new IllegalArgumentException("Unknown aggregate operation: " + operation);
        }
        if (bitStream01 == null) {
            return new WindowAggregateResult(0, 0, initialAggregate(operation));
        }
        String s = bitStream01.trim();
        if (s.isEmpty()) {
            return new WindowAggregateResult(0, 0, initialAggregate(operation));
        }
        for (int i = 0; i < s.length(); i++) {
            char bit = s.charAt(i);
            if (bit != '0' && bit != '1') {
                throw new IllegalArgumentException("bitStream01 must contain only '0'/'1'.");
            }
        }
        if (EXPONENT_BITS <= 0 || EXPONENT_BITS > 11) {
            throw new IllegalArgumentException("EXPONENT_BITS must be in (0, 11].");
        }
        if (ULP_BITS <= 0 || ULP_BITS > 11) {
            throw new IllegalArgumentException("ULP_BITS must be in (0, 11].");
        }

        BitStringInput in = new BitStringInput(s);
        double aggregate = initialAggregate(operation);
        int decodedCount = 0;
        int selectedCount = 0;

        Integer zeroFlag0 = in.readBitsAsIntOrNull(1);
        if (zeroFlag0 == null) {
            return new WindowAggregateResult(0, 0, aggregate);
        }

        BigDecimal firstValue = BigDecimal.ZERO;
        double firstDouble;
        if (zeroFlag0 == 1) {
            firstDouble = 0.0;
        } else {
            Integer signBit = in.readBitsAsIntOrNull(1);
            Integer ulpPlaces = in.readBitsAsIntOrNull(4);
            Integer expUnbiased = readSignMagnitudeOrNull(in, 11);
            if (signBit == null || ulpPlaces == null || expUnbiased == null) {
                return new WindowAggregateResult(0, 0, aggregate);
            }
            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
            long mantissaBits = in.readMantissaBitsOrMinusOne(manSave);
            if (mantissaBits < 0L) {
                return new WindowAggregateResult(0, 0, aggregate);
            }
            firstDouble = reconstructFromMantissaBits(
                    signBit, expUnbiased, mantissaBits, ulpPlaces);
            firstValue = BigDecimal.valueOf(firstDouble).stripTrailingZeros();
        }
        if (isSelected(0, fromInclusive, toExclusive)) {
            aggregate = addAggregate(operation, aggregate, firstDouble);
            selectedCount++;
        }
        decodedCount = 1;

        while (in.hasRemaining()) {
            Integer zeroFlag = in.readBitsAsIntOrNull(1);
            if (zeroFlag == null) break;
            boolean selected = isSelected(decodedCount, fromInclusive, toExclusive);

            if (zeroFlag == 1) {
                if (selected) {
                    aggregate = addAggregate(operation, aggregate, firstValue.doubleValue());
                    selectedCount++;
                }
                decodedCount++;
                continue;
            }

            Integer signBit = in.readBitsAsIntOrNull(1);
            if (signBit == null) break;
            Integer ulpDelta = readSignMagnitudeOrNull(in, ULP_BITS);
            int ulpPlaces;
            if (ulpDelta == null) {
                Integer ulpAbs = in.readBitsAsIntOrNull(4);
                if (ulpAbs == null) break;
                ulpPlaces = ulpAbs;
            } else {
                ulpPlaces = getUlpPlaces(firstValue) + ulpDelta;
            }

            Integer expFlag = in.readBitsAsIntOrNull(1);
            if (expFlag == null) break;
            int expLen = expFlag == 0 ? EXPONENT_BITS : 11;
            Integer expUnbiased = readSignMagnitudeOrNull(in, expLen);
            if (expUnbiased == null) break;

            int manSave = calcManSaveFromExpAndUlp(expUnbiased, ulpPlaces);
            long mantissaBits = in.readMantissaBitsOrMinusOne(manSave);
            if (mantissaBits < 0L) break;
            double delta = reconstructFromMantissaBits(
                    signBit, expUnbiased, mantissaBits, ulpPlaces);
            if (selected) {
                double value = firstValue.add(BigDecimal.valueOf(delta))
                        .stripTrailingZeros().doubleValue();
                aggregate = addAggregate(operation, aggregate, value);
                selectedCount++;
            }
            decodedCount++;
        }

        return new WindowAggregateResult(decodedCount, selectedCount, aggregate);
    }

    private static boolean isSelected(int index, int fromInclusive, int toExclusive) {
        return index >= fromInclusive && index < toExclusive;
    }

    private static double initialAggregate(int operation) {
        if (operation == 2) return Double.POSITIVE_INFINITY;
        if (operation == 3) return Double.NEGATIVE_INFINITY;
        return 0.0;
    }

    private static double addAggregate(int operation, double aggregate, double value) {
        if (operation == 0 || operation == 1) return aggregate + value;
        if (operation == 2) return Math.min(aggregate, value);
        return Math.max(aggregate, value);
    }

    /** String 版 bit reader：顺序读，不做 byte 对齐。 */
    private static final class BitStringInput {
        private final String s;
        private int pos = 0;

        BitStringInput(String s) { this.s = s; }

        boolean hasRemaining() { return pos < s.length(); }

        Integer readBitsAsIntOrNull(int n) {
            if (n <= 0) return 0;
            if (pos + n > s.length()) return null;
            int val = 0;
            for (int i = 0; i < n; i++) {
                val = (val << 1) | (s.charAt(pos++) == '1' ? 1 : 0);
            }
            return val;
        }

        String readBitsAs01StringOrNull(int n) {
            if (n <= 0) return "";
            if (pos + n > s.length()) return null;
            String sub = s.substring(pos, pos + n);
            pos += n;
            return sub;
        }

        /** Reads all mantissa bits, retaining at most the leading 52, aligned as IEEE-754. */
        long readMantissaBitsOrMinusOne(int n) {
            if (n <= 0) return 0L;
            if (pos + n > s.length()) return -1L;
            int retained = Math.min(n, 52);
            long value = 0L;
            for (int i = 0; i < retained; i++) {
                value = (value << 1) | (s.charAt(pos++) == '1' ? 1L : 0L);
            }
            pos += n - retained;
            return retained < 52 ? value << (52 - retained) : value;
        }
    }

    /** String 版 sign-magnitude：若读到 "-0"(sign=1 且 magnitude=0) 作为 overflow marker，则返回 null。 */
    private static Integer readSignMagnitudeOrNull(BitStringInput in, int length) {
        Integer sign = in.readBitsAsIntOrNull(1);
        if (sign == null) return null;
        Integer mag = in.readBitsAsIntOrNull(length - 1);
        if (mag == null) return null;
        if (sign == 1 && mag == 0) return null; // overflow marker "-0"
        return (sign == 1) ? -mag : mag;
    }

// ================= END NEW CODE =================

    public static void main(String[] args) throws Exception {
        runDecompressBinStream();
    }
}
