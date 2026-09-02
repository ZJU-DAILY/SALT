package SALT;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decoder that matches Our_With_Encode output where:
 *  - First line is meta: [WIN_BITS:4][EXPONENT_BITS:4][ULP_BITS:4][BLOCK_SIZE:20]
 *  - Then each data value is on its own line as a 0/1 bitstring
 *  - Additionally, before decoding the value at indices where (blockCount % BLOCK_SIZE == BLOCK_SIZE-1),
 *    the encoder inserts one codebook line in the SAME file:
 *      [minCountBits:5][size:16][entry0][entry1]...
 *    Each entry is fixed: sign(1) + ulpDelta(5, sign-magnitude) + expDelta(11, sign-magnitude) = 17 bits
 *
 * Notes:
 *  - Data-line delta fields still use EXPONENT_BITS / ULP_BITS from meta (as in the encoder).
 *  - Codebook line uses fixed widths: ULP=5, EXP=11 (per your requirement).
 */
public class SALTE_Decompress {

    // --- fixed widths for codebook line (per your latest change) ---
    private static final int CODEBOOK_SIGN_BITS = 1;
    private static final int CODEBOOK_ULP_BITS  = 5;   // <-- ulp \u82B1\u8D39 5 \u4F4D
    private static final int CODEBOOK_EXP_BITS  = 11;
    private static final int CODEBOOK_ENTRY_BITS = CODEBOOK_SIGN_BITS + CODEBOOK_ULP_BITS + CODEBOOK_EXP_BITS; // 17

    private static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

    // Codebook entry key (same semantics as encoder's Key): s=sign(0+/1-), u=ulpDelta, e=expDelta
    private static final class Key {
        final int s;
        final int u;
        final int e;
        Key(int s, int u, int e) { this.s = s; this.u = u; this.e = e; }
    }

    private static final class SignMag {
        final int value;        // signed value (if not overflow)
        final boolean overflow; // true if this is the encoder's "out of range" sentinel (-0)
        SignMag(int value, boolean overflow) { this.value = value; this.overflow = overflow; }
    }


    // ------------------ Binary bit input helpers ------------------

    /** Simple MSB-first bit reader (same idea as OurDecompress.BitInput). */
    static class BitInput {
        private final InputStream in;
        private int curByte = 0;
        private int bitPos = 8; // 0..7 used; 8 means need a new byte
        private final ArrayDeque<Integer> pushBack = new ArrayDeque<>();

        BitInput(InputStream in) { this.in = in; }

        private int readBit() throws IOException {
            if (!pushBack.isEmpty()) {
                return pushBack.pollFirst();
            }
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
            if (n <= 0) return 0;
            int val = 0;
            for (int i = 0; i < n; i++) {
                int b = readBit();
                if (b < 0) return null;
                val = (val << 1) | b;
            }
            return val;
        }

        Long readBitsAsLongOrNull(int n) throws IOException {
            if (n <= 0) return 0L;
            long val = 0L;
            for (int i = 0; i < n; i++) {
                int b = readBit();
                if (b < 0) return null;
                val = (val << 1) | (long) b;
            }
            return val;
        }
        /** Push back the last-read bits so they can be read again (MSB-first). */
        void unreadBits(long value, int n) {
            if (n <= 0) return;
            for (int i = n - 1; i >= 0; i--) {
                int bit = (int) ((value >> i) & 1L);
                pushBack.addFirst(bit);
            }
        }

    }

    /** Read a sign-magnitude integer from the bitstream, with "-0" used as overflow sentinel. */
    private static SignMag readSignMagnitude(BitInput in, int totalBits) throws IOException {
        if (totalBits <= 0) return new SignMag(0, false);

        Integer signObj = in.readBitsAsIntOrNull(1);
        if (signObj == null) return null;
        int signBit = signObj;

        int magBits = totalBits - 1;
        long mag = 0L;
        if (magBits > 0) {
            Long magObj = in.readBitsAsLongOrNull(magBits);
            if (magObj == null) return null;
            mag = magObj;
        }

        boolean overflow = (signBit == 1 && mag == 0L && magBits > 0);
        int magInt = (int) mag;
        int val = (signBit == 1) ? -magInt : magInt;
        return new SignMag(val, overflow);
    }

    /** Decode exponent: normal => prevExp + expDelta; overflow => next 11 bits is absolute exponent (sign-magnitude). */
    private static int decodeExponent(BitInput in, BigDecimal prevDelta, SignMag expDeltaSM) throws IOException {
        int prevExp = getIeeeExponent(prevDelta);
        if (!expDeltaSM.overflow) {
            return prevExp + expDeltaSM.value;
        }
        SignMag exp11 = readSignMagnitude(in, CODEBOOK_EXP_BITS);
        if (exp11 == null) throw new EOFException("EOF while reading exp11 (overflow)");
        return exp11.value;
    }

    /** Decode ulpPlaces: normal => prevUlp + ulpDelta; overflow => next 4 bits is absolute ulpPlaces (unsigned). */
    private static int decodeUlpPlaces(BitInput in, BigDecimal prevDelta, SignMag ulpDeltaSM) throws IOException {
        int prevUlp = getUlpPlaces(prevDelta);
        if (!ulpDeltaSM.overflow) {
            int ulp = prevUlp + ulpDeltaSM.value;
            return Math.max(ulp, 0);
        }
        Long ulp4Obj = in.readBitsAsLongOrNull(4);
        if (ulp4Obj == null) throw new EOFException("EOF while reading ulpPlaces4 (overflow)");
        return (int) (long) ulp4Obj;
    }

    /** Read the codebook segment inserted by encoder: [minCountBits:5][size:16][entry0:17]... */
    private static CodebookParseResult readCodebook(BitInput in) throws IOException {
        Integer minCountBitsObj = in.readBitsAsIntOrNull(5);
        Integer sizeObj = in.readBitsAsIntOrNull(16);
        if (minCountBitsObj == null || sizeObj == null) return null;

        int minCountBits = minCountBitsObj;
        int size = sizeObj;

        List<Key> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Integer sObj = in.readBitsAsIntOrNull(1);
            if (sObj == null) return null;
            int s = sObj;

            SignMag uSM = readSignMagnitude(in, CODEBOOK_ULP_BITS);
            if (uSM == null) return null;

            SignMag eSM = readSignMagnitude(in, CODEBOOK_EXP_BITS);
            if (eSM == null) return null;

            entries.add(new Key(s, uSM.value, eSM.value));
        }
        return new CodebookParseResult(minCountBits, entries);
    }

    public static void main(String[] args) throws Exception {
        // 1) \u5982\u679C\u4F20\u53C2\uFF1A\u6309\u201C\u5355\u6587\u4EF6\u6A21\u5F0F\u201D\u8FD0\u884C\uFF08\u517C\u5BB9\u4F60\u539F\u6765\u7684\u7528\u6CD5\uFF09
        if (args.length >= 1) {
            String inPath  = args[0];
            String outPath = (args.length >= 2)
                    ? args[1]
                    : inPath.replaceAll("(?i)\\.bin$", "") + ".csv";

            System.out.println("Decode input : " + inPath);
            System.out.println("Decode output: " + outPath);
            decodeFile(new File(inPath), new File(outPath));
            return;
        }

        // 2) \u4E0D\u4F20\u53C2\uFF1A\u6279\u91CF\u5904\u7406\u5F53\u524D\u6587\u4EF6\u5939\u6240\u6709 bin
        File dir = new File("."); // \u5F53\u524D\u5DE5\u4F5C\u76EE\u5F55
        File[] txtFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".bin"));

        if (txtFiles == null || txtFiles.length == 0) {
            System.out.println("No .bin files found in current directory: " + dir.getAbsolutePath());
            return;
        }

        Arrays.sort(txtFiles, Comparator.comparing(File::getName)); // \u8BA9\u8F93\u51FA\u66F4\u7A33\u5B9A\uFF08\u53EF\u9009\uFF09

        for (File inFile : txtFiles) {
            String baseName = inFile.getName().replaceAll("(?i)\\.bin$", "");
            File outFile = new File(inFile.getParentFile(), baseName + ".csv");

            System.out.println("Decode input : " + inFile.getName());
            System.out.println("Decode output: " + outFile.getName());

            try {
                decodeFile(inFile, outFile);
            } catch (Exception e) {
                System.err.println("Failed on file: " + inFile.getName());
                e.printStackTrace();
            }
        }
    }


    private static void decodeFile(File inFile, File outFile) throws Exception {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(inFile));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {

            BitInput in = new BitInput(fis);

            // header (32 bits): [WIN_BITS:4][EXPONENT_BITS:4][ULP_BITS:4][BLOCK_SIZE:20]
            Integer winBitsI = in.readBitsAsIntOrNull(4);
            Integer expBitsI = in.readBitsAsIntOrNull(4);
            Integer ulpBitsI = in.readBitsAsIntOrNull(4);
            Long blockSizeL = in.readBitsAsLongOrNull(20);

            // v2 header: [TOTAL_VALUES:32].
            // To avoid the "extra value" caused by byte-alignment padding, the encoder writes the total number of original values.
            // Since there is no MAGIC anymore, we use a simple heuristic:
            //   - if the 32-bit number looks like a plausible count, treat it as TOTAL_VALUES;
            //   - otherwise, push these 32 bits back and decode as the old format.
            long totalValues = Long.MAX_VALUE;
            Long totalMaybe = in.readBitsAsLongOrNull(32);
            if (totalMaybe == null) {
                throw new EOFException("Unexpected EOF while reading TOTAL_VALUES");
            }
            // Heuristic threshold: adjust if your datasets are larger.
            if (totalMaybe > 0 && totalMaybe <= 50_000_000L) {
                totalValues = totalMaybe;
            } else {
                in.unreadBits(totalMaybe, 32);
            }

            if (winBitsI == null || expBitsI == null || ulpBitsI == null || blockSizeL == null) {
                throw new EOFException("Empty input file or missing 32-bit header");
            }

            int WIN_BITS = winBitsI;
            int EXPONENT_BITS = expBitsI;
            int ULP_BITS = ulpBitsI;
            int BLOCK_SIZE = blockSizeL.intValue();

            int winCapacity = 1 << WIN_BITS;

            BigDecimal[] valueWin = new BigDecimal[winCapacity];
            BigDecimal[] deltaWin = new BigDecimal[winCapacity];
            Arrays.fill(valueWin, BigDecimal.valueOf(0.1));
            Arrays.fill(deltaWin, BigDecimal.valueOf(0.1));
            int winIndex = 0;

            int blockCount = 0;

            // active codebook (index -> Key). Only used when blockCount >= BLOCK_SIZE-1
            List<Key> codebook = new ArrayList<>();
            int minCountBits = 0;

            long decoded = 0L;

            while (true) {
                if (decoded >= totalValues) break;
                // Codebook segment is inserted BEFORE decoding the value when (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1)
                if (BLOCK_SIZE > 0 && (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1)) {
                    CodebookParseResult cb = readCodebook(in);
                    if (cb == null) break; // clean EOF
                    minCountBits = cb.minCountBits;
                    codebook = cb.entries;
                }

                Integer bestIObj = in.readBitsAsIntOrNull(WIN_BITS);
                if (bestIObj == null) break; // clean EOF
                int bestI = bestIObj;

                BigDecimal prevValue = valueWin[(winIndex + bestI) % winCapacity];
                BigDecimal prevDelta = deltaWin[(winIndex + bestI) % winCapacity];

                BigDecimal delta;

                if (blockCount < BLOCK_SIZE - 1) {
                    // warmup:
                    //   [WIN][zeroFlag:1] OR
                    //   [WIN][0][sign][expDelta][(exp11?)] [ulpDelta][(ulpPlaces4?)] [mantissaBits]
                    Integer zeroFlagObj = in.readBitsAsIntOrNull(1);
                    if (zeroFlagObj == null) break;

                    if (zeroFlagObj == 1) {
                        delta = BigDecimal.ZERO;
                    } else {
                        Integer signObj = in.readBitsAsIntOrNull(1);
                        if (signObj == null) break;
                        int sign = signObj;

                        SignMag expDeltaSM = readSignMagnitude(in, EXPONENT_BITS);
                        if (expDeltaSM == null) break;
                        int exponent = decodeExponent(in, prevDelta, expDeltaSM);

                        SignMag ulpDeltaSM = readSignMagnitude(in, ULP_BITS);
                        if (ulpDeltaSM == null) break;
                        int ulpPlaces = decodeUlpPlaces(in, prevDelta, ulpDeltaSM);

                        int manBits = calcManBits(exponent, ulpPlaces);
                        long mantissaTop = 0L;
                        if (manBits > 0) {
                            Long manObj = in.readBitsAsLongOrNull(manBits);
                            if (manObj == null) break;
                            mantissaTop = manObj;
                        }

                        double d = buildDouble(sign, exponent, mantissaTop, manBits);
                        delta = toScaledBigDecimal(d, ulpPlaces);
                    }
                } else {
                    // steady-state:
                    //   [WIN][code:minCountBits] then:
                    //    - zero:   code == 2^minCountBits-1
                    //    - escape: code == 2^minCountBits-2  => sign + expDelta(+exp11?) + ulpDelta(+ulpPlaces4?) + mantissa
                    //    - hit:    codebook index => (sign, ulpDelta, expDelta) from codebook + mantissa
                    Integer codeObj = in.readBitsAsIntOrNull(minCountBits);
                    if (codeObj == null) break;
                    int code = codeObj;

                    int zeroCode = (1 << minCountBits) - 1;
                    int escapeCode = (1 << minCountBits) - 2;

                    int sign;
                    int exponent;
                    int ulpPlaces;

                    if (code == zeroCode) {
                        delta = BigDecimal.ZERO;
                    } else {
                        if (code == escapeCode) {
                            Integer signObj = in.readBitsAsIntOrNull(1);
                            if (signObj == null) break;
                            sign = signObj;

                            SignMag expDeltaSM = readSignMagnitude(in, EXPONENT_BITS);
                            if (expDeltaSM == null) break;
                            exponent = decodeExponent(in, prevDelta, expDeltaSM);

                            SignMag ulpDeltaSM = readSignMagnitude(in, ULP_BITS);
                            if (ulpDeltaSM == null) break;
                            ulpPlaces = decodeUlpPlaces(in, prevDelta, ulpDeltaSM);
                        } else {
                            // codebook hit
                            if (code < 0 || code >= codebook.size()) {
                                throw new IllegalStateException("Codebook index out of range: " + code + " size=" + codebook.size());
                            }
                            Key k = codebook.get(code);
                            sign = k.s;

                            int prevExp = getIeeeExponent(prevDelta);
                            exponent = prevExp + k.e;

                            int prevUlp = getUlpPlaces(prevDelta);
                            ulpPlaces = prevUlp + k.u;
                            if (ulpPlaces < 0) ulpPlaces = 0; // safety
                        }

                        int manBits = calcManBits(exponent, ulpPlaces);
                        long mantissaTop = 0L;
                        if (manBits > 0) {
                            Long manObj = in.readBitsAsLongOrNull(manBits);
                            if (manObj == null) break;
                            mantissaTop = manObj;
                        }

                        double d = buildDouble(sign, exponent, mantissaTop, manBits);
                        delta = toScaledBigDecimal(d, ulpPlaces);
                    }
                }

                BigDecimal value = prevValue.add(delta);
                out.println(value.toPlainString());
                decoded++;

                // update windows\uFF08\u4E0E\u538B\u7F29\u7AEF/Our.java \u5BF9\u9F50\uFF1Adelta==0 \u4E0D\u63A8\u8FDB\uFF09
                if (delta.signum() != 0) {
                    valueWin[winIndex] = value;
                    deltaWin[winIndex] = delta;
                    winIndex = (winIndex + 1) % winCapacity;
                }

                blockCount++;
            }
        }
    }




    // ------------------ Codebook parsing ------------------

    private static final class CodebookParseResult {
        final int minCountBits;
        final List<Key> entries;
        CodebookParseResult(int minCountBits, List<Key> entries) { this.minCountBits = minCountBits; this.entries = entries; }
    }

    /**
     * Codebook line format:
     *  [minCountBits:5][size:16][entry0:17][entry1:17]...
     * entry: [sign:1][ulpDelta:5 sign-mag][expDelta:11 sign-mag]
     */
    private static CodebookParseResult parseCodebookLine(String cbLine) {
        if (cbLine.length() < 5 + 16) {
            throw new IllegalArgumentException("Bad codebook line (too short): " + cbLine.length());
        }
        int pos = 0;
        int minCountBits = parseUnsigned(cbLine.substring(pos, pos + 5));
        pos += 5;

        int size = parseUnsigned(cbLine.substring(pos, pos + 16));
        pos += 16;

        List<Key> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (pos + CODEBOOK_ENTRY_BITS > cbLine.length()) {
                throw new IllegalArgumentException("Bad codebook line: declared size=" + size + " but line ends early at i=" + i);
            }

            int s = (cbLine.charAt(pos++) == '1') ? 1 : 0;

            int u = decodeSignMagnitude(cbLine.substring(pos, pos + CODEBOOK_ULP_BITS)).value;
            pos += CODEBOOK_ULP_BITS;

            int e = decodeSignMagnitude(cbLine.substring(pos, pos + CODEBOOK_EXP_BITS)).value;
            pos += CODEBOOK_EXP_BITS;

            entries.add(new Key(s, u, e));
        }

        return new CodebookParseResult(minCountBits, entries);
    }

    // ------------------ Delta decoding helpers ------------------

    /**
     * Decode sign-magnitude bitstring.
     * Overflow sentinel used by encoder when abs(value) > max: sign=1 and magnitude all 0 ("-0").
     */
    private static SignMag decodeSignMagnitude(String bits) {
        if (bits == null || bits.isEmpty()) return new SignMag(0, false);

        char signBit = bits.charAt(0);
        String magBits = bits.substring(1);

        boolean magAllZero = true;
        for (int i = 0; i < magBits.length(); i++) {
            if (magBits.charAt(i) != '0') { magAllZero = false; break; }
        }

        // encoder overflow sentinel: "-0"
        if (signBit == '1' && magAllZero && magBits.length() > 0) {
            return new SignMag(0, true);
        }

        int mag = magBits.isEmpty() ? 0 : parseUnsigned(magBits);
        int val = (signBit == '1') ? -mag : mag;
        return new SignMag(val, false);
    }

    private static int computeExponent(BigDecimal prevDelta, SignMag expDeltaSM, String line, int EXPONENT_BITS, int posAfterExpDelta) {
        int prevExp = getIeeeExponent(prevDelta);
        if (!expDeltaSM.overflow) {
            return prevExp + expDeltaSM.value;
        }
        // overflow => next 11 bits are absolute exponent (sign-magnitude 11)
        String exp11 = line.substring(posAfterExpDelta, posAfterExpDelta + 11);
        return decodeSignMagnitude(exp11).value;
    }

    private static int computeUlpPlaces(BigDecimal prevDelta, SignMag ulpDeltaSM, String line, int posAfterUlpDelta) {
        int prevUlp = getUlpPlaces(prevDelta);
        if (!ulpDeltaSM.overflow) {
            int ulp = prevUlp + ulpDeltaSM.value;
            return Math.max(ulp, 0);
        }
        // overflow => next 4 bits are absolute ulpPlaces (unsigned)
        String ulp4 = line.substring(posAfterUlpDelta, posAfterUlpDelta + 4);
        return parseUnsigned(ulp4);
    }

    private static int calcManBits(int exponent, int ulpPlaces) {
        // Mirrors encoder's getManSave() logic but using exponent/ulpPlaces directly.
        int E = exponent;
        int ulp = Math.max(ulpPlaces, 0);

        double t = Math.floor(-ulp * LOG2_10);

        int bits;
        if (E < 0) {
            bits = (int) Math.round(E - t);  // t is negative => E + |t|
        } else {
            int intBits = E + 1;             // for >=1, integer bits ~ exponent+1
            int extra = (int) Math.ceil(ulp * LOG2_10);
            bits = intBits + extra - 1;
        }

        if (bits < 0) bits = 0;
        if (bits > 52) bits = 52;
        return bits;
    }

    private static double buildDouble(int sign, int exponent, long mantissaTop, int mantissaTopBits) {
        if (mantissaTopBits < 0) mantissaTopBits = 0;
        if (mantissaTopBits > 52) mantissaTopBits = 52;

        long mantissa = (mantissaTopBits == 0) ? 0L : (mantissaTop << (52 - mantissaTopBits));

        int biased = exponent + 1023;
        if (biased < 0) biased = 0;
        if (biased > 2046) biased = 2046;

        long raw = 0L;
        if (sign == 1) raw |= (1L << 63);
        raw |= ((long) biased & 0x7FFL) << 52;
        raw |= mantissa & ((1L << 52) - 1);

        return Double.longBitsToDouble(raw);
    }

    private static BigDecimal toScaledBigDecimal(double d, int ulpPlaces) {
        // Match OurDecompress's Python-style quantize:
        //   Decimal(str(val)).quantize(1e-ulp, ROUND_UP)
        // In Java, RoundingMode.UP rounds away from zero; to match the original code's handling,
        // we keep the explicit negative branch.
        BigDecimal bd = new BigDecimal(Double.toString(d));
        int scale = Math.max(ulpPlaces, 0);
        if (d >= 0.0) {
            return bd.setScale(scale, RoundingMode.UP);
        } else {
            return bd.negate().setScale(scale, RoundingMode.UP).negate();
        }
    }

    private static BigDecimal addWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        // Align with OurDecompress: direct BigDecimal addition without forcing scale alignment.
        if (a == null) a = BigDecimal.ZERO;
        if (b == null) b = BigDecimal.ZERO;
        return a.add(b);
    }

    private static int getIeeeExponent(BigDecimal x) {
        if (x == null || x.signum() == 0) return -1023;
        return Math.getExponent(x.doubleValue());
    }

    private static int getUlpPlaces(BigDecimal x) {
        if (x == null) return 0;
        BigDecimal stripped = x.stripTrailingZeros();
        int scale = stripped.scale();
        return Math.max(scale, 0);
    }

    // ------------------ bit parsing helpers ------------------

    private static int parseUnsigned(String bits) {
        if (bits == null || bits.isEmpty()) return 0;
        return Integer.parseInt(bits, 2);
    }

    private static long parseUnsignedLong(String bits) {
        if (bits == null || bits.isEmpty()) return 0L;
        return Long.parseLong(bits, 2);
    }
}
