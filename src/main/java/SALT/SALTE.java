package SALT;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class SALTE {

    // \u5E38\u91CF
    private static final int ZERO_BITS = 1;
    private static final int SIGN_BITS = 1;      // \u7B26\u53F7\u4F4D
    private static final int WIN_BITS = 2;       // \u7A97\u53E3\u5927\u5C0F\u7F16\u7801\u4F4D\u6570
    private static final int EXPONENT_BITS = 3;  // \u6307\u6570\u53D8\u5316\u7F16\u7801\u4F4D\u6570

    private static final int CODE_BITS = 0;  // \u6307\u6570\u53D8\u5316\u7F16\u7801\u4F4D\u6570,0:auto
    private static final int ULP_BITS = 3;       // \u7CBE\u5EA6\u53D8\u5316\u7F16\u7801\u4F4D\u6570
    private static final int BLOCK_SIZE = 10000;  // \u7EDF\u8BA1\u5206\u5757\u5927\u5C0F
    private static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);

    // \u7D27\u51D1\u4E0D\u53EF\u53D8\u952E\uFF1A\u4EC5 3 \u4E2A int\uFF0C\u653E\u8FDB Map/Set
    static final class Key {
        final int s, u, e; // sign, ulpDelta, expDelta
        Key(int s, int u, int e){ this.s=s; this.u=u; this.e=e; }

        @Override public int hashCode(){
            int h = s * 0x9E3779B9;
            h = (h ^ (u + 0x85EBCA6B)) * 0xC2B2AE35;
            return h ^ e;
        }
        @Override public boolean equals(Object o){
            if (this==o) return true;
            if (o instanceof Key) {
                Key k=(Key)o; return s==k.s && u==k.u && e==k.e;
            }
            if (o instanceof Probe) { // \u5141\u8BB8\u7528 Probe \u6765\u67E5 contains
                Probe p=(Probe)o; return s==p.s && u==p.u && e==p.e;
            }
            return false;
        }
    }

    // \u4EC5\u7528\u4E8E contains \u67E5\u8BE2\u7684\u53EF\u53D8\u201C\u63A2\u9488\u201D\uFF0C\u4E0D\u653E\u5165 Map/Set
    static final class Probe {
        int s, u, e;
        void set(int s,int u,int e){ this.s=s; this.u=u; this.e=e; }
        @Override public int hashCode(){
            int h = s * 0x9E3779B9;
            h = (h ^ (u + 0x85EBCA6B)) * 0xC2B2AE35;
            return h ^ e;
        }
        @Override public boolean equals(Object o){
            if (o instanceof Key) {
                Key k=(Key)o; return s==k.s && u==k.u && e==k.e;
            }
            if (o instanceof Probe) {
                Probe p=(Probe)o; return s==p.s && u==p.u && e==p.e;
            }
            return false;
        }
    }

    static BigDecimal[] readFirstColumnAsBigDecimals(String filename) throws IOException {
        List<String> rawStrings = new ArrayList<>();

        // \u7B2C\u4E00\u6B65\uFF1A\u53EA\u4EE5\u5B57\u7B26\u4E32\u5F62\u5F0F\u8BFB\u53D6
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filename), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    rawStrings.add(null); // \u4FDD\u7559\u7A7A\u884C
                    continue;
                }

                // \u53D6\u7B2C\u4E00\u5217
                String first = line.split(",", -1)[0].trim();

                // \u2705 \u65B0\u589E\uFF1A\u53BB\u6389 UTF-8 BOM\uFF08\u5E38\u89C1\u4E8E\u6587\u4EF6\u7B2C\u4E00\u884C\u5F00\u5934\uFF09
                if (!first.isEmpty() && first.charAt(0) == '\uFEFF') {
                    first = first.substring(1);
                }

                // \u53BB\u6389\u5305\u88F9\u7684\u5F15\u53F7
                if (first.startsWith("\"") && first.endsWith("\"") && first.length() >= 2) {
                    first = first.substring(1, first.length() - 1);
                }

                rawStrings.add(first.isEmpty() ? null : first);
            }
        }

        // \u7B2C\u4E8C\u6B65\uFF1A\u7EDF\u4E00\u8F6C\u6362\u4E3A BigDecimal
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



    // \u53D6\u5F97 IEEE754 \u53CC\u7CBE\u5EA6 \u65E0\u504F\u6307\u6570\uFF08\u4E0E Python get_ieee_exponent \u4E00\u81F4\uFF1A\u8FD4\u56DE unbiased\uFF09
    static int getIeeeExponent(BigDecimal x) {
        if (x == null || x.signum() == 0) {
            return -1023; // \u5BF9\u5E94 IEEE 754 \u96F6\u503C\u6307\u6570
        }
        double d = x.doubleValue();
        return Math.getExponent(d); // Java 1.5+ \u81EA\u5E26\u65B9\u6CD5\uFF0C\u76F8\u5F53\u4E8E doubleToRawLongBits \u7684\u6307\u6570
    }


    // \u7EDF\u8BA1\u5341\u8FDB\u5236\u5C0F\u6570\u4F4D\u6570\uFF08\u5BF9\u5E94 Python \u7684 Decimal(...).normalize() \u518D\u770B exponent\uFF09
    static int getUlpPlaces(BigDecimal x) {
        if (x == null) {
            return 0;
        }
        BigDecimal stripped = x.stripTrailingZeros();
        int scale = stripped.scale();
        return Math.max(scale, 0);
    }



    // \u8BA1\u7B97\u9700\u8981\u4FDD\u5B58\u7684\u5C3E\u6570\u4F4D\u6570\uFF08\u5BF9\u5E94 Python \u7684 get_man_save\uFF09
    static int getManSave(BigDecimal x) {
        if (x == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }
        BigDecimal ax = x.abs();

        // \u5C0F\u4E8E 1 \u7684\u60C5\u51B5
        if (ax.compareTo(BigDecimal.ONE) < 0) {
            int E = getIeeeExponent(ax);     // \u65E0\u504F\u6307\u6570
            int ulp = getUlpPlaces(ax);      // \u5341\u8FDB\u5236\u5C0F\u6570\u4F4D\u6570
            double t = Math.floor(-ulp * LOG2_10);
            return (int) Math.round(E - t);  // t \u4E3A\u8D1F\u6570\uFF0C\u6240\u4EE5\u7B49\u4EF7\u4E8E E + |t|
        }
        // >= 1 \u7684\u60C5\u51B5
        else {
            long intPart = ax.longValue();

            long absInt = Math.abs(intPart);
            if (intPart == Long.MIN_VALUE) {   // \u6781\u7AEF\u4FDD\u62A4
                absInt = Long.MAX_VALUE;
            }
            int intBits = (absInt != 0) ? (64 - Long.numberOfLeadingZeros(absInt)) : 1;
            int ulp = getUlpPlaces(ax);        // \u5341\u8FDB\u5236\u7CBE\u5EA6
            int extra = (int) Math.ceil(ulp * LOG2_10);
            return intBits + extra - 1;
        }
    }

    // \u201C\u6309\u539F\u59CB\u7CBE\u5EA6\u76F8\u51CF\u201D\uFF0C\u5E76\u6309 mode \u51B3\u5B9A\u4FDD\u7559\u5C0F\u6570\u4F4D\uFF1B\u5BF9\u5E94 Python subtract_with_original_precision
    static BigDecimal subtractWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        // \u83B7\u53D6\u4E24\u4E2A\u6570\u7684\u6709\u6548\u5C0F\u6570\u4F4D\u6570\uFF08\u7CBE\u5EA6\uFF09
        int pa = Math.max(a.scale(), 0);
        int pb = Math.max(b.scale(), 0);

        // \u53D6\u8F83\u5927\u7684\u5C0F\u6570\u4F4D\u6570\uFF0C\u4EE5\u4FDD\u7559\u66F4\u9AD8\u7CBE\u5EA6
        int p = Math.max(pa, pb);

        // \u6267\u884C\u51CF\u6CD5\u5E76\u6309\u539F\u59CB\u7CBE\u5EA6\u820D\u5165
        return a.subtract(b)
                .setScale(p, RoundingMode.HALF_UP)
                .stripTrailingZeros();

    }



    public static Map<Key, Integer> getTopN(Map<Key, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<Key, Integer>comparingByValue().reversed())
                .limit(n)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    public static int getRank(Map<Key, Integer> map, Object key) {
        int idx = 0;
        for (Key k : map.keySet()) {
            if (k.equals(key)) return idx;
            idx++;
        }
        return -1;
    }

    private static String toFixedBinary(int value, int length) {
        String s = Integer.toBinaryString(value);
        if (s.length() > length) {
            throw new IllegalArgumentException(
                    "value " + value + " \u8D85\u8FC7\u957F\u5EA6 " + length + " \u80FD\u8868\u793A\u7684\u8303\u56F4");
        }
        return String.format("%" + length + "s", s).replace(' ', '0');
    }
    public static String toSignMagnitudeBinary(int value, int length) {
        int magLen = length - 1;           // \u6570\u503C\u4F4D\u957F\u5EA6
        long maxMagnitude = (1L << magLen) - 1;  // \u6700\u5927\u53EF\u8868\u793A\u7EDD\u5BF9\u503C

        long absVal = Math.abs((long) value);    // \u7528 long \u9632\u6B62\u6EA2\u51FA

        // \u8D85\u8303\u56F4\uFF1A\u8F93\u51FA\u539F\u7801 -0
        if (absVal > maxMagnitude) {
            StringBuilder nz = new StringBuilder(length);
            nz.append('1');                // \u7B26\u53F7\u4F4D 1
            for (int i = 0; i < magLen; i++) {
                nz.append('0');            // \u6570\u503C\u4F4D\u5168 0
            }
            return nz.toString();
        }

        int magnitude = (int) absVal;
        String magBin = Integer.toBinaryString(magnitude);

        // \u5DE6\u4FA7\u8865 0 \u5230 magLen \u4F4D
        StringBuilder sb = new StringBuilder(magLen);
        for (int i = magBin.length(); i < magLen; i++) {
            sb.append('0');
        }
        sb.append(magBin);

        char signBit = (value < 0) ? '1' : '0';
        return signBit + sb.toString();
    }

    private static void writeCodebookLine(BitOutputStream bitOut, int minCountBits, Map<Key, Integer> codebook) throws IOException {
        int size = (codebook == null) ? 0 : codebook.size();

        // entry: sign(1) + ulp(5) + exp(11) = 17 bits
        StringBuilder sb = new StringBuilder(5 + 16 + size * 17);

        // minCountBits\uFF1A\u56FA\u5B9A 5 \u4F4D
        sb.append(toFixedBinary(minCountBits, 5));

        // size\uFF1A\u56FA\u5B9A 16 \u4F4D
        sb.append(toFixedBinary(size, 16));

        if (size > 0) {
            for (Key k : codebook.keySet()) {
                // \u56FA\u5B9A\u4F4D\u6570\u8981\u6C42\uFF1Asign 1bit\uFF0Culp 5bit\uFF0C\u6307\u6570 11bit
                if (k.s != 0 && k.s != 1) throw new IllegalArgumentException("Key.s \u5FC5\u987B\u662F 0/1: " + k.s);

                // 5 \u4F4D\u539F\u7801\uFF08\u542B\u7B26\u53F7\u4F4D\uFF09\u7EDD\u5BF9\u503C\u6700\u5927 15
                if (Math.abs(k.u) > 15)   throw new IllegalArgumentException("Key.u \u8D85\u51FA 5 \u4F4D\u539F\u7801[-15,15]: " + k.u);

                // 11 \u4F4D\u539F\u7801\uFF08\u542B\u7B26\u53F7\u4F4D\uFF09\u7EDD\u5BF9\u503C\u6700\u5927 1023
                if (Math.abs(k.e) > 1023) throw new IllegalArgumentException("Key.e \u8D85\u51FA 11 \u4F4D\u539F\u7801[-1023,1023]: " + k.e);

                sb.append(k.s == 0 ? '0' : '1');          // sign:1
                sb.append(toSignMagnitudeBinary(k.u, 5)); // ulp:5  <-- \u6539\u8FD9\u91CC
                sb.append(toSignMagnitudeBinary(k.e, 11));// exp:11
            }
        }

        bitOut.writeBitString(sb.toString());
    }


    public static String getDoubleMantissaBits(double value, int numBits) {
        if (numBits == 0) {
            return "";
        }

        long bits = Double.doubleToRawLongBits(value);

        // \u4F4E 52 \u4F4D\u662F\u5C3E\u6570\uFF08fraction\uFF09
        long mantissa = bits & ((1L << 52) - 1);

        StringBuilder sb = new StringBuilder(numBits);
        // \u4ECE mantissa \u7684\u6700\u9AD8\u4F4D\uFF08bit 51\uFF09\u5F80\u4E0B\u53D6 numBits \u4F4D
        for (int i = 51; i >= 52 - numBits; i--) {
            long bit = (mantissa >>> i) & 1L;
            sb.append(bit == 0 ? '0' : '1');
        }

        return sb.toString();
    }
    public static String removeExtension(String fileName) {
        if (fileName == null) return null;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) { // \u786E\u4FDD\u4E0D\u662F\u4EE5 . \u5F00\u5934\u7684\u9690\u85CF\u6587\u4EF6
            return fileName.substring(0, dotIndex);
        }
        return fileName;    // \u6CA1\u6709\u540E\u7F00\u5C31\u539F\u6837\u8FD4\u56DE
    }
    public static void main(String[] args) throws Exception {
        File dir = new File("datasets/forCRUD");
        if (!dir.isDirectory()) {
            System.err.println("dataset/ \u76EE\u5F55\u4E0D\u5B58\u5728\u3002\u8BF7\u5C06 CSV \u6587\u4EF6\u653E\u5728 dataset/ \u4E0B\u3002");
            return;
        }


        // \u8F93\u51FA\u7ED3\u679C CSV
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("results_with_encode.csv"), StandardCharsets.UTF_8))) {

            out.println("\u6587\u4EF6\u540D,\u538B\u7F29\u7387,\u65F6\u95F4");

            int winCapacity = 1 << WIN_BITS; // \u7A97\u53E3\u5927\u5C0F

            // \u904D\u5386 dataset \u4E0B\u7684\u6240\u6709\u6587\u4EF6
            List<File> files = Arrays.stream(Objects.requireNonNull(dir.listFiles()))
                    .filter(File::isFile)
                    .sorted(Comparator.comparing(File::getName))
                    .collect(Collectors.toList());

            for (File f : files) {
                // \u4E3A\u5F53\u524D\u6587\u4EF6\u521B\u5EFA\u4E00\u4E2A bit \u8F93\u51FA\u6587\u4EF6
                String bitFileName = removeExtension(f.getName()) + ".bin";
                String deltaLogName = removeExtension(f.getName()) + "_best_delta.csv";
                try (PrintWriter deltaOut = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(deltaLogName), StandardCharsets.UTF_8))) {
                    try (BitOutputStream bitOut = new BitOutputStream(
                            new BufferedOutputStream(new FileOutputStream(bitFileName)))) {
                        String meta = toFixedBinary(WIN_BITS, 4)
                                + toFixedBinary(EXPONENT_BITS, 4)
                                + toFixedBinary(ULP_BITS, 4)
                                + toFixedBinary(BLOCK_SIZE, 20);
                        bitOut.writeBitString(meta);  // Write meta header bits

                        // \u8BFB\u53D6 CSV \u7B2C\u4E00\u5217\u4E3A BigDecimal
                        BigDecimal[] datas = readFirstColumnAsBigDecimals(f.getPath());
                        // Write MAGIC + total value count right after header.
                        // This lets the decoder stop exactly at the end of real data and ignore byte-alignment padding.
                        bitOut.writeBitString(toFixedBinary(datas.length, 32));

                        double startTime = System.nanoTime();
                        double sumBits = 0.0;

                        // --- \u521D\u59CB\u5316\u7A97\u53E3\u4E3A\u6570\u7EC4 ---
                        BigDecimal[] valueWin = new BigDecimal[winCapacity];
                        BigDecimal[] deltaWin = new BigDecimal[winCapacity];
                        Arrays.fill(valueWin, BigDecimal.valueOf(0.1));
                        Arrays.fill(deltaWin, BigDecimal.valueOf(0.1));
                        int winIndex = 0; // \u73AF\u5F62\u7F13\u51B2\u533A\u5F53\u524D\u5199\u5165\u4F4D\u7F6E

                        int blockCount = 0;
                        Map<Key, Integer> counts = new HashMap<>();
                        Map<Key, Integer> prev_counts = new HashMap<>();
                        Probe probe = new Probe();

                        int minCountBits=0;
                        for (int idx = 0; idx < datas.length; idx++) {

                            if (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1) {
                                if (CODE_BITS == 0){

                                    // ---------- \u81EA\u52A8\u6A21\u5F0F\uFF1A\u52A8\u6001\u9009\u62E9\u6700\u4F18 countBits ----------
                                    int distinct = counts.size();             // \u5F53\u524D\u5757\u4E0D\u540C\u7B26\u53F7\u7684\u79CD\u7C7B\u6570
                                    int countBits = 1;                        // \u5F53\u524D\u5C1D\u8BD5\u7684\u7801\u5B57\u4F4D\u6570
                                    long minBlockSize = 1000L * BLOCK_SIZE;    // \u521D\u59CB\u5316\u4E00\u4E2A\u5F88\u5927\u7684\u6210\u672C\u503C
                                    minCountBits = 1;                     // \u4FDD\u5B58\u6700\u4F18\u7801\u5B57\u4F4D\u6570

                                    // \u679A\u4E3E\u53EF\u80FD\u7684 countBits\uFF0C\u76F4\u5230\u80FD\u8986\u76D6\u6240\u6709\u7B26\u53F7
                                    while ((1 << countBits) < distinct) {
                                        int cap = (1 << countBits) - 2;  // \u80FD\u653E\u8FDB\u7801\u672C\u7684\u70ED\u95E8\u7B26\u53F7\u4E2A\u6570
                                        int saved = 0;                // \u80FD\u8282\u7EA6\u7684\u7A7A\u95F4
                                        int keySum=0;
                                        if (cap > 0) {
                                            // \u53D6\u51FA\u73B0\u9891\u7387\u6700\u9AD8\u7684 cap \u4E2A\u7B26\u53F7
                                            Map<Key, Integer> top = getTopN(counts, cap);
                                            for (Map.Entry<Key, Integer> entry : top.entrySet()) {
                                                Key k = entry.getKey();
                                                int freq = entry.getValue();
                                                int key_bits_cost=0;
                                                int s = k.s;
                                                key_bits_cost+=1; // sign
                                                int u = k.u;
                                                if (Math.abs(u)>(1<<(ULP_BITS-1))){
                                                    key_bits_cost+=ULP_BITS+4;
                                                }else{
                                                    key_bits_cost+=ULP_BITS;
                                                }
                                                int e = k.e;
                                                if (Math.abs(e)>(1<<(EXPONENT_BITS-1))){
                                                    key_bits_cost+=EXPONENT_BITS+11;
                                                }else {
                                                    key_bits_cost+=EXPONENT_BITS;
                                                }
                                                saved+=(key_bits_cost-countBits)*freq;
                                                keySum+=freq;
                                            }
                                        }

                                        // \u6210\u672C\u6A21\u578B\uFF1A
                                        // \u547D\u4E2D\u70ED\u95E8\u7B26\u53F7\uFF1AcountBits \u4F4D\uFF1B
                                        // \u672A\u547D\u4E2D\uFF1AcountBits+16 \u4F4D\uFF1B
                                        // \u7801\u672C\u672C\u8EAB\u5B58\u50A8\u5F00\u9500\uFF1A(2^countBits - 1)*16
                                        long cost = countBits * (BLOCK_SIZE - keySum) - saved;
//                                    System.out.println(cost);

                                        if (cost < minBlockSize) {
                                            minBlockSize = cost;
                                            minCountBits = countBits;
                                        }

                                        countBits++;
                                    }
                                    // \u751F\u6210\u5F53\u524D\u5757\u7684\u6700\u4F18\u7801\u672C
                                    int finalCap = (1 << minCountBits) - 2;
                                    Map<Key, Integer> codebook;
                                    if (finalCap > 0) {
                                        codebook = getTopN(counts, finalCap);
                                    } else {
                                        codebook = Collections.emptyMap();
                                    }


                                    // \u66F4\u65B0\u4E0A\u4E00\u5757\u7684\u7801\u672C\u4F9B\u4E0B\u4E00\u5757\u4F7F\u7528
                                    prev_counts = new LinkedHashMap<>(codebook);
                                    // \u5C06\u672C\u5757\u751F\u6210\u7684\u7801\u672C\u5199\u5165\u540C\u4E00\u4E2A\u8F93\u51FA\u6587\u4EF6\uFF08\u5355\u72EC\u4E00\u884C bit \u4E32\uFF09
                                    writeCodebookLine(bitOut, minCountBits, prev_counts);
                                }
                                else{
                                    int Code_Cap= (1<<CODE_BITS)-2;
                                    prev_counts=getTopN(counts,Code_Cap);
                                    minCountBits = CODE_BITS;
                                    // \u5C06\u672C\u5757\u751F\u6210\u7684\u7801\u672C\u5199\u5165\u540C\u4E00\u4E2A\u8F93\u51FA\u6587\u4EF6\uFF08\u5355\u72EC\u4E00\u884C bit \u4E32\uFF09
                                    writeCodebookLine(bitOut, minCountBits, prev_counts);
                                }
//                            System.out.println(minCountBits);
                                counts.clear();
                            }

                            BigDecimal value = datas[idx];

                            if (value == null) {
                                continue;
//                        break;
                            }

                            value = value.stripTrailingZeros();

                            int minBits = 100;
                            BigDecimal minDelta = BigDecimal.valueOf(0.1);
                            BigDecimal bestPrevDelta = BigDecimal.valueOf(0.1);


                            int bestSign = 0;
                            int bestUlpDelta = 0;
                            int bestExpDelta = 0;
                            int bestI=0;
                            int bestManSave=100;
                            int bestHashIndex=100;


                            // --- \u904D\u5386\u7A97\u53E3 ---
                            for (int i = 0; i < winCapacity; i++) {
                                int tmpBits;
                                if (blockCount<BLOCK_SIZE-1){
                                    tmpBits = WIN_BITS + SIGN_BITS + ZERO_BITS;
                                }else{
                                    tmpBits = WIN_BITS + minCountBits + SIGN_BITS;
                                }


                                BigDecimal prevValue = valueWin[(winIndex + i) % winCapacity];
                                BigDecimal delta = subtractWithOriginalPrecision(value, prevValue);
                                BigDecimal prevDelta = deltaWin[(winIndex + i) % winCapacity];

                                if (delta.signum() == 0) {
                                    if (blockCount < BLOCK_SIZE -1) {
                                        minBits = WIN_BITS + ZERO_BITS;
                                        bestI=i;
                                        minDelta = delta;
                                        break;
                                    }else{
                                        minBits = WIN_BITS + minCountBits;
                                        bestI=i;
                                        minDelta = delta;
                                        break;
                                    }

                                }




                                // \u7CBE\u5EA6\u5DEE
                                int ulpDelta = getUlpPlaces(delta) - getUlpPlaces(prevDelta);

                                int sign = (delta.signum() > 0 ? 0 : 1);
                                // \u6307\u6570\u5DEE
                                int expDelta = getIeeeExponent(delta) - getIeeeExponent(prevDelta);

                                int manSave = getManSave(delta);


                                probe.set(sign, ulpDelta, expDelta);
                                if (prev_counts.containsKey(probe)) {
                                    tmpBits = WIN_BITS + minCountBits + manSave;
                                }else{
                                    if (Math.abs(ulpDelta) < (1 << (ULP_BITS - 1))) {
                                        tmpBits += ULP_BITS;
                                    } else {
                                        tmpBits += ULP_BITS + 4;
                                    }
                                    if (Math.abs(expDelta) < (1 << (EXPONENT_BITS - 1))) {
                                        tmpBits += EXPONENT_BITS;
                                    } else {
                                        tmpBits += EXPONENT_BITS + 11;
                                    }
                                    tmpBits += manSave;
                                }
                                if (tmpBits < minBits) {
                                    minBits = tmpBits;
                                    minDelta = delta;
                                    bestSign = sign;
                                    bestUlpDelta = ulpDelta;
                                    bestExpDelta = expDelta;
                                    bestI = i;
                                    bestManSave = manSave;
                                    bestPrevDelta = prevDelta;
                                    if (prev_counts.containsKey(probe)) {
                                        bestHashIndex= getRank(prev_counts, probe);
                                    }else{
                                        bestHashIndex = (1<<minCountBits) - 2;
                                    }

//                                if(f.getName().equals("City-temp.csv") && blockCount==9999){
//                                    System.out.println("bestI\uFF1A"+bestI);
//                                    System.out.println("bestSign\uFF1A"+bestSign);
//                                    System.out.println("ulpDelta\uFF1A"+bestUlpDelta);
//                                    System.out.println("expDelta\uFF1A"+bestExpDelta);
//                                    System.out.println("manSave\uFF1A"+bestManSave);
//                                    System.out.println("hashIndex\uFF1A"+bestHashIndex);
//                                    System.out.println("minBits\uFF1A"+minBits);
//                                    System.out.println("====================================");
//                                }


                                }

                            }
                            deltaOut.println(bestI+","+minDelta.toPlainString()+","+bestSign+","+getUlpPlaces(minDelta)+","+getIeeeExponent(minDelta)+","+bestManSave+","+bestPrevDelta.toPlainString()+","+getUlpPlaces(bestPrevDelta)+","+getIeeeExponent(bestPrevDelta));


                            // \u51C6\u5907\u5199\u5165\u5B57\u7B26\u4E32
                            String str = String.format("%" + WIN_BITS + "s",
                                            Integer.toBinaryString(bestI))
                                    .replace(' ', '0');
                            if(blockCount<BLOCK_SIZE-1){
                                if (minDelta.signum() == 0) {
                                    str += "1";
                                }
                                else {
                                    str += "0";
                                    if (minDelta.signum() > 0) {
                                        str += "0";
                                    } else {
                                        str += "1";
                                    }

                                    String expStr = toSignMagnitudeBinary(bestExpDelta, EXPONENT_BITS);
                                    str += expStr;

                                    if (Math.abs(bestExpDelta) >= 1 << (EXPONENT_BITS - 1)) {
                                        str += toSignMagnitudeBinary(getIeeeExponent(minDelta), 11);
                                    }

                                    String ulpStr = toSignMagnitudeBinary(bestUlpDelta, ULP_BITS);
                                    str += ulpStr;

                                    if (Math.abs(bestUlpDelta) >= 1 << (ULP_BITS - 1)) {
                                        str += String.format("%4s",
                                                        Integer.toBinaryString(Math.min(15, getUlpPlaces(minDelta))))
                                                .replace(' ', '0');
                                    }

                                    str += getDoubleMantissaBits(minDelta.doubleValue(), bestManSave);
                                }
                            }else{
                                if (minDelta.signum() == 0) {
                                    str += toFixedBinary((1 << minCountBits) - 1, minCountBits);
                                }
                                else {
                                    if (bestHashIndex == (1<<minCountBits) - 2){
                                        str += toFixedBinary((1 << minCountBits) - 2, minCountBits);
//                                    str += "0";
                                        if (minDelta.signum() > 0) {
                                            str += "0";
                                        } else {
                                            str += "1";
                                        }

                                        String expStr = toSignMagnitudeBinary(bestExpDelta, EXPONENT_BITS);
                                        str += expStr;

                                        if (Math.abs(bestExpDelta) >= 1 << (EXPONENT_BITS - 1)) {
                                            str += toSignMagnitudeBinary(getIeeeExponent(minDelta), 11);
                                        }

                                        String ulpStr = toSignMagnitudeBinary(bestUlpDelta, ULP_BITS);
                                        str += ulpStr;

                                        if (Math.abs(bestUlpDelta) >= 1 << (ULP_BITS - 1)) {
                                            str += String.format("%4s",
                                                            Integer.toBinaryString(Math.min(15, getUlpPlaces(minDelta))))
                                                    .replace(' ', '0');
                                        }
                                    }else{
                                        str += toFixedBinary(bestHashIndex, minCountBits);
                                    }

                                    str += getDoubleMantissaBits(minDelta.doubleValue(), bestManSave);
                                }
                            }


                            if (str.length() != minBits) {
                                System.out.println("\u957F\u5EA6\u8BA1\u7B97\u9519\u8BEF");
                                System.out.println("\u6587\u4EF6");
                                System.out.println(f.getName());
                                System.out.println("\u4F4D\u7F6E\uFF1A");
                                System.out.println(blockCount);
                                System.out.println(minBits);
                                System.out.println(str.length());
                                System.out.println("bestManSave:");
                                System.out.println(bestManSave);
                                System.out.println("bestExpDelta:");
                                System.out.println(bestExpDelta);
                                System.out.println("bestUlpDelta:");
                                System.out.println(bestUlpDelta);
                                System.out.println("bestHashIndex:");
                                System.out.println(bestHashIndex);
                                System.out.println("minCountBits:");
                                System.out.println(minCountBits);
                                System.out.println(str);
                                throw new Exception();
                            }

                            // \u2705 \u5728\u8FD9\u91CC\u771F\u6B63\u5199\u5165\u6587\u4EF6\uFF08\u6BCF\u4E2A\u5757\u4E00\u884C\uFF09
                            bitOut.writeBitString(str);

                            // --- \u66F4\u65B0\u7A97\u53E3\uFF08\u4E0E Our.java \u5BF9\u9F50\uFF1A\u53EA\u6709\u975E\u96F6 delta \u624D\u63A8\u8FDB\uFF09---
                            if (minDelta.signum() != 0) {
                                valueWin[winIndex] = value;
                                deltaWin[winIndex] = minDelta;
                                winIndex = (winIndex + 1) % winCapacity;

                                Key k = new Key(bestSign, bestUlpDelta, bestExpDelta);
                                counts.put(k, counts.getOrDefault(k, 0) + 1);
                            }
                            sumBits += minBits;
                            blockCount++;
                        }

                        double ratio = sumBits / (1.0 * Math.max(blockCount, 1));
//                double ratio = sumBits / (64.0 * Math.max(datas.length, 1));
                        double endTime = System.nanoTime();
                        System.out.println("\u8017\u65F6(ms): " + (endTime - startTime)/1000000);
                        System.out.printf("%s: %f%n", f.getName(), ratio);
                        out.printf("%s,%f,%f%n", f.getName(), ratio, (endTime - startTime)/1000000);
                    }
                }


            }
        }
    }



    // \u5C06 '0'/'1' \u6BD4\u7279\u4E32\u5199\u5165\u771F\u6B63\u7684\u4E8C\u8FDB\u5236\u6587\u4EF6\uFF08\u6309\u4F4D\u6253\u5305\u6210\u5B57\u8282\uFF09
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
                writeBit((int) ((value >>> i) & 1L));
            }
        }

        public void writeBitString(String bits01) throws IOException {
            for (int i = 0; i < bits01.length(); i++) {
                char c = bits01.charAt(i);
                writeBit(c == '1' ? 1 : 0);
            }
        }

        /** \u82E5\u9700\u8981\u5728\u67D0\u4E9B\u5B57\u6BB5\u540E\u5BF9\u9F50\u5230\u6574\u5B57\u8282\uFF0C\u53EF\u8C03\u7528\u5B83 */
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

}

