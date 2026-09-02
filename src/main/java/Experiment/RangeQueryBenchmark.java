package Experiment;

import SALT.SALTSQL;
import SALT.SALTSQL_CRUD;
import algorithms.AlgorithmsManager;
import algorithms.Decoder;
import algorithms.Encoder;
import enums.DataTypeEnums;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Benchmarks indexed SALT+ range materialization against raw access and
 * baseline codecs that fully decompress the column for every query.
 */
public final class RangeQueryBenchmark {
    private static final int[] RANGE_LENGTHS = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096};
    private static final int DEFAULT_BASELINE_WARMUP_FULL_DECOMPRESSIONS = 120;
    static final String[] DEFAULT_BASELINES = {
            "ALP", "DeXOR", "Gorilla", "ElfPlus", "Chimp", "Chimp128",
            "Elf", "Camel", "SALTE"
    };

    private static final int WIN_BITS = 7;
    private static final int WINDOW_SIZE = 1 << WIN_BITS;
    private static final int EXPONENT_BITS = 5;
    private static final int ULP_BITS = 2;
    private static final int DEFAULT_WINDOW_NUM_BITS = WIN_BITS + 2;
    private static final int DEFAULT_WINDOW_LEN_BITS = WIN_BITS + 6;
    private static final int DEFAULT_FENWICK_BITS = 32;

    private static volatile double blackhole;

    private RangeQueryBenchmark() {
    }

    private static final class Config {
        Path dataset = Paths.get("datasets", "Overall", "City-temp.csv");
        int column = 1;
        int queries = 100;
        int warmupQueries = 10;
        int baselineWarmupFullDecompressions = DEFAULT_BASELINE_WARMUP_FULL_DECOMPRESSIONS;
        long seed = 20260827L;
        Path storageRoot = Paths.get("storage", "range_query");
        Path output = Paths.get("results", "range_query", "range_query_results.csv");
        List<String> baselines = new ArrayList<String>(Arrays.asList(DEFAULT_BASELINES));
    }

    static final class Dataset {
        final List<BigDecimal> decimals;
        final double[] doubles;

        Dataset(List<BigDecimal> decimals) {
            this.decimals = decimals;
            this.doubles = new double[decimals.size()];
            for (int i = 0; i < decimals.size(); i++) {
                this.doubles[i] = decimals.get(i).doubleValue();
            }
        }
    }

    private static final class Result {
        final String method;
        final String accessMode;
        final int rangeLength;
        final long[] latencyNs;
        final double checksum;

        Result(String method, String accessMode, int rangeLength, long[] latencyNs, double checksum) {
            this.method = method;
            this.accessMode = accessMode;
            this.rangeLength = rangeLength;
            this.latencyNs = latencyNs;
            this.checksum = checksum;
        }

        double averageNs() {
            long sum = 0L;
            for (long value : latencyNs) sum += value;
            return (double) sum / latencyNs.length;
        }

        double percentileNs(double percentile) {
            long[] sorted = latencyNs.clone();
            Arrays.sort(sorted);
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            if (index < 0) index = 0;
            if (index >= sorted.length) index = sorted.length - 1;
            return sorted[index];
        }

        double standardDeviationNs() {
            double average = averageNs();
            double squaredDifferenceSum = 0.0;
            for (long value : latencyNs) {
                double difference = value - average;
                squaredDifferenceSum += difference * difference;
            }
            return Math.sqrt(squaredDifferenceSum / latencyNs.length);
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);
        Dataset dataset = readDataset(config.dataset, config.column);
        int maxRange = RANGE_LENGTHS[RANGE_LENGTHS.length - 1];
        if (dataset.doubles.length < maxRange) {
            throw new IllegalArgumentException("Dataset contains only " + dataset.doubles.length
                    + " valid values, but the largest range is " + maxRange);
        }

        String datasetName = removeExtension(config.dataset.getFileName().toString());
        System.out.println("[Setup] Dataset: " + config.dataset.toAbsolutePath());
        System.out.println("[Setup] Valid records: " + dataset.doubles.length);
        Path datasetStorage = config.storageRoot.resolve(datasetName);
        Files.createDirectories(datasetStorage);

        Path saltDirectory = datasetStorage.resolve("SALTPlus");
        Files.createDirectories(saltDirectory);
        String saltBase = saltDirectory.resolve(datasetName).toString();
        System.out.println("[Setup] Building SALT+ data and Fenwick indexes...");
        buildSaltPlusStore(dataset.decimals, saltBase);
        System.out.println("[Setup] SALT+ is ready.");

        Map<String, Path> baselineFiles = new LinkedHashMap<String, Path>();
        Path baselineDirectory = datasetStorage.resolve("baselines");
        Files.createDirectories(baselineDirectory);
        int preparedBaselines = 0;
        for (String method : config.baselines) {
            System.out.printf(Locale.ROOT, "[Setup %d/%d] Compressing and verifying %s...%n",
                    preparedBaselines + 1, config.baselines.size(), method);
            Path compressed = baselineDirectory.resolve(datasetName + "."
                    + method.toLowerCase(Locale.ROOT));
            compressBaseline(method, compressed, dataset.doubles);
            verifyBaseline(method, compressed, dataset.doubles);
            baselineFiles.put(method, compressed);
            preparedBaselines++;
            System.out.printf(Locale.ROOT, "[Setup %d/%d] %s is ready.%n",
                    preparedBaselines, config.baselines.size(), method);
        }

        List<Result> results = new ArrayList<Result>();
        Map<Integer, int[]> queryStarts = buildQueryStarts(
                dataset.doubles.length, config.queries + config.warmupQueries, config.seed);
        System.out.println("[Setup] All range lengths share the same random start positions.");
        int totalBenchmarkTasks = RANGE_LENGTHS.length * (2 + baselineFiles.size());
        int completedBenchmarkTasks = 0;
        long benchmarkStartedNs;
        List<Map.Entry<String, Path>> baselineEntries =
                new ArrayList<Map.Entry<String, Path>>(baselineFiles.entrySet());

        try (SALTSQL_CRUD saltPlus = SALTSQL_CRUD.openByName(saltBase)) {
            if (saltPlus.getTotalRecords() != dataset.doubles.length) {
                throw new IllegalStateException("SALT+ index contains " + saltPlus.getTotalRecords()
                        + " values, expected " + dataset.doubles.length);
            }
            System.out.println("[Setup] Prewarming SALT+ indexed range paths...");
            prewarmSaltPlus(saltPlus, queryStarts);
            benchmarkStartedNs = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "[Benchmark] Starting %d tasks: %d measured queries, %d fast-path warmups "
                            + "per configuration, %d fixed full-decompression warmups per baseline.%n",
                    totalBenchmarkTasks, config.queries, config.warmupQueries,
                    config.baselineWarmupFullDecompressions);

            System.out.println("[Benchmark] Phase 1/2: Raw and SALT+ (isolated from baseline GC).");
            for (int rangeLength : RANGE_LENGTHS) {
                int[] starts = queryStarts.get(rangeLength);
                verifySaltPlusRange(saltPlus, dataset.decimals, starts[config.warmupQueries], rangeLength);
            }
            results.addAll(benchmarkFastMethodsInterleaved(
                    dataset.doubles, saltPlus, queryStarts, config));
            completedBenchmarkTasks += RANGE_LENGTHS.length * 2;
            printProgress(completedBenchmarkTasks, totalBenchmarkTasks, benchmarkStartedNs,
                    "all lengths, methods=Raw/SALT+");

            System.out.println("[Benchmark] Phase 2/2: original full-decompression baselines; "
                    + "all lengths are warmed first and then measured round-robin.");
            for (Map.Entry<String, Path> baseline : baselineEntries) {
                results.addAll(benchmarkBaselineInterleaved(
                        baseline.getKey(), baseline.getValue(), dataset.doubles.length,
                        queryStarts, config));
                completedBenchmarkTasks += RANGE_LENGTHS.length;
                printProgress(completedBenchmarkTasks, totalBenchmarkTasks, benchmarkStartedNs,
                        "all lengths, method=" + baseline.getKey());
            }
        }

        Collections.sort(results, new Comparator<Result>() {
            @Override
            public int compare(Result left, Result right) {
                int byLength = Integer.compare(left.rangeLength, right.rangeLength);
                if (byLength != 0) return byLength;
                return left.method.compareTo(right.method);
            }
        });

        writeResults(config.output, datasetName, dataset.doubles.length, config, results);
        printResults(results);
        System.out.println("Results written to " + config.output.toAbsolutePath());
        blackhole += results.size();
    }

    private static void printProgress(int completed, int total, long startedNs, String finishedTask) {
        long elapsedNs = System.nanoTime() - startedNs;
        double percent = 100.0 * completed / total;
        long estimatedTotalNs = completed == 0 ? 0L : (long) ((double) elapsedNs * total / completed);
        long remainingNs = Math.max(0L, estimatedTotalNs - elapsedNs);
        System.out.printf(Locale.ROOT,
                "[Benchmark %d/%d, %5.1f%%] Finished %s | elapsed %s | ETA %s%n",
                completed, total, percent, finishedTask,
                formatDuration(elapsedNs), formatDuration(remainingNs));
    }

    private static String formatDuration(long nanoseconds) {
        long totalSeconds = Math.max(0L, nanoseconds / 1_000_000_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static Config parseArgs(String[] args) {
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--dataset".equals(arg)) config.dataset = Paths.get(requireValue(args, ++i, arg));
            else if ("--column".equals(arg)) config.column = Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--queries".equals(arg)) config.queries = Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--warmup".equals(arg)) config.warmupQueries = Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--baseline-warmup".equals(arg)) config.baselineWarmupFullDecompressions =
                    Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--seed".equals(arg)) config.seed = Long.parseLong(requireValue(args, ++i, arg));
            else if ("--storage".equals(arg)) config.storageRoot = Paths.get(requireValue(args, ++i, arg));
            else if ("--output".equals(arg)) config.output = Paths.get(requireValue(args, ++i, arg));
            else if ("--baselines".equals(arg)) {
                config.baselines.clear();
                String[] names = requireValue(args, ++i, arg).split(",");
                for (String name : names) {
                    if (!name.trim().isEmpty()) config.baselines.add(name.trim());
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsageAndExit();
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (config.column < 0) throw new IllegalArgumentException("--column must be >= 0");
        if (config.queries <= 0) throw new IllegalArgumentException("--queries must be > 0");
        if (config.warmupQueries < 0) throw new IllegalArgumentException("--warmup must be >= 0");
        if (config.baselineWarmupFullDecompressions < 0) {
            throw new IllegalArgumentException("--baseline-warmup must be >= 0");
        }
        if (config.baselines.isEmpty()) throw new IllegalArgumentException("At least one baseline is required");
        return config;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value after " + option);
        return args[index];
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java Experiment.RangeQueryBenchmark [options]");
        System.out.println("  --dataset <csv>       default: datasets/Overall/Wind-Speed.csv");
        System.out.println("  --column <zero-based> default: 1");
        System.out.println("  --queries <count>     default: 100 per range length");
        System.out.println("  --warmup <count>      default: 10 per method and range length");
        System.out.println("  --baseline-warmup <n> fixed full decompressions per baseline; default: 120");
        System.out.println("  --seed <long>         default: 20260827");
        System.out.println("  --baselines <csv>     default: ALP,DeXOR,Gorilla,ElfPlus,Chimp,"
                + "Chimp128,Elf,Camel,SALTE");
        System.out.println("  --storage <dir>       default: storage/range_query");
        System.out.println("  --output <csv>        default: results/range_query/range_query_results.csv");
        System.exit(0);
    }

    static Dataset readDataset(Path path, int requestedColumn) throws IOException {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripBom(line).trim();
                if (line.isEmpty()) continue;
                String[] fields = line.split(",", -1);
                int column = requestedColumn;
                if (column >= fields.length && fields.length == 1) column = 0;
                if (column >= fields.length) continue;
                String text = unquote(stripBom(fields[column].trim()));
                if (text.isEmpty() || "nan".equalsIgnoreCase(text)) continue;
                try {
                    values.add(new BigDecimal(text));
                } catch (NumberFormatException ignored) {
                    // Header or another non-numeric row.
                }
            }
        }
        if (values.isEmpty()) throw new IOException("No numeric values found in " + path);
        return new Dataset(values);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF'
                ? value.substring(1) : value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static void buildSaltPlusStore(List<BigDecimal> values, String basePath) throws IOException {
        List<Long> windowBitLengths = new ArrayList<Long>();
        List<Long> windowRecordCounts = new ArrayList<Long>();
        List<String> encodedWindows = new ArrayList<String>();

        for (int from = 0; from < values.size(); from += WINDOW_SIZE) {
            int to = Math.min(values.size(), from + WINDOW_SIZE);
            List<BigDecimal> window = values.subList(from, to);
            String bits = SALTSQL.encodeAsOneWindow01String(window, EXPONENT_BITS, ULP_BITS);
            encodedWindows.add(bits);
            windowBitLengths.add((long) bits.length());
            windowRecordCounts.add((long) window.size());
        }

        try (SALTSQL.BitOutputStream out = new SALTSQL.BitOutputStream(
                Files.newOutputStream(Paths.get(basePath + ".bin")))) {
            out.writeBits(WIN_BITS, 4);
            out.writeBits(EXPONENT_BITS, 4);
            out.writeBits(ULP_BITS, 4);
            for (String bits : encodedWindows) out.writeBitString(bits);
        }

        int numWidth = Math.max(DEFAULT_WINDOW_NUM_BITS, bitsRequired(max(windowRecordCounts)));
        int lenWidth = Math.max(DEFAULT_WINDOW_LEN_BITS, bitsRequired(max(windowBitLengths)));
        long[] numFenwick = buildFenwick(windowRecordCounts);
        long[] lenFenwick = buildFenwick(windowBitLengths);
        int numFenwickWidth = Math.max(DEFAULT_FENWICK_BITS, bitsRequired(max(numFenwick)));
        int lenFenwickWidth = Math.max(DEFAULT_FENWICK_BITS, bitsRequired(max(lenFenwick)));

        writePackedSidecar(Paths.get(basePath + "_window_num.bin"), numWidth, windowRecordCounts);
        writePackedSidecar(Paths.get(basePath + "_window_len.bin"), lenWidth, windowBitLengths);
        writePackedFenwick(Paths.get(basePath + "_num_fenwick.bin"), numFenwickWidth, numFenwick);
        writePackedFenwick(Paths.get(basePath + "_len_fenwick.bin"), lenFenwickWidth, lenFenwick);
    }

    private static long max(List<Long> values) {
        long max = 0L;
        for (long value : values) if (value > max) max = value;
        return max;
    }

    private static long max(long[] values) {
        long max = 0L;
        for (long value : values) if (value > max) max = value;
        return max;
    }

    private static int bitsRequired(long value) {
        if (value <= 0) return 1;
        return 64 - Long.numberOfLeadingZeros(value);
    }

    private static long[] buildFenwick(List<Long> values) {
        long[] tree = new long[values.size() + 1];
        for (int i = 1; i <= values.size(); i++) {
            long value = values.get(i - 1);
            for (int j = i; j <= values.size(); j += j & -j) tree[j] += value;
        }
        return tree;
    }

    private static void writePackedSidecar(Path path, int width, List<Long> values) throws IOException {
        try (SALTSQL.BitOutputStream out = new SALTSQL.BitOutputStream(Files.newOutputStream(path))) {
            out.writeBits(width, 8);
            out.writeBits(values.size(), 32);
            for (long value : values) out.writeBits(value, width);
        }
    }

    private static void writePackedFenwick(Path path, int width, long[] tree) throws IOException {
        try (SALTSQL.BitOutputStream out = new SALTSQL.BitOutputStream(Files.newOutputStream(path))) {
            out.writeBits(width, 8);
            out.writeBits(tree.length - 1, 32);
            for (int i = 1; i < tree.length; i++) out.writeBits(tree[i], width);
        }
    }

    static void compressBaseline(String method, Path output, double[] values) throws Exception {
        Files.deleteIfExists(output);
        Encoder encoder = AlgorithmsManager.getEncoder(DataTypeEnums.DOUBLE.getType(), method, output.toString());
        for (double value : values) encoder.encode(value);
        encoder.close();
        encoder.flush();
    }

    static void verifyBaseline(String method, Path compressed, double[] expected) throws Exception {
        Decoder decoder = AlgorithmsManager.getDecoder(
                DataTypeEnums.DOUBLE.getType(), method, compressed.toString());
        for (int i = 0; i < expected.length; i++) {
            double actual = decoder.decodeDouble();
            if (!sameDouble(expected[i], actual)) {
                throw new IllegalStateException(method + " failed correctness at index " + i
                        + ": expected=" + expected[i] + ", actual=" + actual);
            }
        }
    }

    static boolean sameDouble(double expected, double actual) {
        if (Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual)) return true;
        double scale = Math.max(1.0, Math.abs(expected));
        return Math.abs(expected - actual) <= Math.ulp(scale);
    }

    private static Map<Integer, int[]> buildQueryStarts(int records, int count, long seed) {
        Map<Integer, int[]> starts = new LinkedHashMap<Integer, int[]>();
        int maximumLength = 0;
        for (int length : RANGE_LENGTHS) maximumLength = Math.max(maximumLength, length);
        int bound = records - maximumLength + 1;
        if (bound <= 0) {
            throw new IllegalArgumentException("Dataset is shorter than the maximum range length");
        }
        Random random = new Random(seed);
        int[] commonPositions = new int[count];
        for (int i = 0; i < count; i++) commonPositions[i] = random.nextInt(bound);
        for (int length : RANGE_LENGTHS) {
            starts.put(length, commonPositions.clone());
        }
        return starts;
    }

    private static void prewarmSaltPlus(SALTSQL_CRUD saltPlus,
                                        Map<Integer, int[]> startsByLength) throws IOException {
        double checksum = 0.0;
        for (int round = 0; round < 20; round++) {
            for (int index = RANGE_LENGTHS.length - 1; index >= 0; index--) {
                int length = RANGE_LENGTHS[index];
                int[] starts = startsByLength.get(length);
                int start = starts[round % starts.length];
                checksum += sum(saltPlus.getRangeAsDoublesByRecordIndex(start, length));
            }
        }
        blackhole += checksum;
    }

    private static void verifySaltPlusRange(SALTSQL_CRUD saltPlus, List<BigDecimal> expected,
                                            int start, int length) throws IOException {
        double[] actual = saltPlus.getRangeAsDoublesByRecordIndex(start, length);
        for (int i = 0; i < length; i++) {
            double expectedValue = expected.get(start + i).doubleValue();
            if (!sameDouble(expectedValue, actual[i])) {
                throw new IllegalStateException("SALT+ range correctness failed at index " + (start + i)
                        + ": expected=" + expectedValue + ", actual=" + actual[i]);
            }
        }
    }

    private static List<Result> benchmarkFastMethodsInterleaved(
            double[] values, SALTSQL_CRUD saltPlus, Map<Integer, int[]> startsByLength,
            Config config) throws IOException {
        Map<Integer, long[]> rawLatency = newLatencyMap(config.queries);
        Map<Integer, long[]> saltLatency = newLatencyMap(config.queries);
        Map<Integer, Double> rawChecksums = newChecksumMap();
        Map<Integer, Double> saltChecksums = newChecksumMap();

        // Complete the warmup for every length before recording any latency.
        for (int round = 0; round < config.warmupQueries; round++) {
            int rotation = round % RANGE_LENGTHS.length;
            for (int offset = 0; offset < RANGE_LENGTHS.length; offset++) {
                int length = RANGE_LENGTHS[(rotation + offset) % RANGE_LENGTHS.length];
                int start = startsByLength.get(length)[round];
                rawChecksums.put(length, rawChecksums.get(length)
                        + sum(Arrays.copyOfRange(values, start, start + length)));
                saltChecksums.put(length, saltChecksums.get(length)
                        + sum(saltPlus.getRangeAsDoublesByRecordIndex(start, length)));
            }
        }

        // Each round measures every length once. Rotating the first length prevents
        // early/late JVM state from being assigned systematically to one length.
        for (int sample = 0; sample < config.queries; sample++) {
            int startIndex = config.warmupQueries + sample;
            int rotation = startIndex % RANGE_LENGTHS.length;
            for (int offset = 0; offset < RANGE_LENGTHS.length; offset++) {
                int length = RANGE_LENGTHS[(rotation + offset) % RANGE_LENGTHS.length];
                int start = startsByLength.get(length)[startIndex];

                long rawBegin = System.nanoTime();
                double rawChecksum = sum(Arrays.copyOfRange(values, start, start + length));
                rawLatency.get(length)[sample] = System.nanoTime() - rawBegin;
                rawChecksums.put(length, rawChecksums.get(length) + rawChecksum);

                long saltBegin = System.nanoTime();
                double saltChecksum = sum(saltPlus.getRangeAsDoublesByRecordIndex(start, length));
                saltLatency.get(length)[sample] = System.nanoTime() - saltBegin;
                saltChecksums.put(length, saltChecksums.get(length) + saltChecksum);
            }
            if ((sample + 1) % 10 == 0 || sample + 1 == config.queries) {
                System.out.printf(Locale.ROOT,
                        "[Raw/SALT+] measured round %d/%d%n", sample + 1, config.queries);
            }
        }

        List<Result> results = new ArrayList<Result>();
        for (int length : RANGE_LENGTHS) {
            double rawChecksum = rawChecksums.get(length);
            double saltChecksum = saltChecksums.get(length);
            results.add(new Result("Raw", "raw_direct", length,
                    rawLatency.get(length), rawChecksum));
            results.add(new Result("SALT+", "indexed_partial_decompression", length,
                    saltLatency.get(length), saltChecksum));
            blackhole += rawChecksum + saltChecksum;
        }
        return results;
    }

    private static List<Result> benchmarkBaselineInterleaved(
            String method, Path compressed, int records,
            Map<Integer, int[]> startsByLength, Config config) throws Exception {
        Map<Integer, long[]> latencyByLength = newLatencyMap(config.queries);
        Map<Integer, Double> checksums = newChecksumMap();

        // Keep decoder/JIT warmup independent of the number of query lengths.
        System.out.printf(Locale.ROOT, "[%s] Prewarming with %d full decompressions.%n",
                method, config.baselineWarmupFullDecompressions);
        for (int warmup = 0; warmup < config.baselineWarmupFullDecompressions; warmup++) {
            int length = RANGE_LENGTHS[warmup % RANGE_LENGTHS.length];
            int[] starts = startsByLength.get(length);
            int start = starts[warmup % starts.length];
            double checksum = fullDecompressAndMaterialize(
                    method, compressed, records, start, length);
            checksums.put(length, checksums.get(length) + checksum);
        }

        for (int sample = 0; sample < config.queries; sample++) {
            int startIndex = config.warmupQueries + sample;
            int rotation = startIndex % RANGE_LENGTHS.length;
            for (int offset = 0; offset < RANGE_LENGTHS.length; offset++) {
                int length = RANGE_LENGTHS[(rotation + offset) % RANGE_LENGTHS.length];
                int start = startsByLength.get(length)[startIndex];
                long begin = System.nanoTime();
                double checksum = fullDecompressAndMaterialize(
                        method, compressed, records, start, length);
                latencyByLength.get(length)[sample] = System.nanoTime() - begin;
                checksums.put(length, checksums.get(length) + checksum);
            }
            if ((sample + 1) % 10 == 0 || sample + 1 == config.queries) {
                System.out.printf(Locale.ROOT,
                        "[%s] measured round %d/%d%n", method, sample + 1, config.queries);
            }
        }

        List<Result> results = new ArrayList<Result>();
        for (int length : RANGE_LENGTHS) {
            double checksum = checksums.get(length);
            results.add(new Result(method, "full_decompression", length,
                    latencyByLength.get(length), checksum));
            blackhole += checksum;
        }
        return results;
    }

    private static Map<Integer, long[]> newLatencyMap(int samples) {
        Map<Integer, long[]> values = new LinkedHashMap<Integer, long[]>();
        for (int length : RANGE_LENGTHS) values.put(length, new long[samples]);
        return values;
    }

    private static Map<Integer, Double> newChecksumMap() {
        Map<Integer, Double> values = new LinkedHashMap<Integer, Double>();
        for (int length : RANGE_LENGTHS) values.put(length, 0.0);
        return values;
    }

    private static double fullDecompressAndMaterialize(String method, Path compressed, int records,
                                                       int start, int length) throws Exception {
        Decoder decoder = AlgorithmsManager.getDecoder(
                DataTypeEnums.DOUBLE.getType(), method, compressed.toString());
        double[] all = new double[records];
        for (int i = 0; i < records; i++) all[i] = decoder.decodeDouble();
        double[] range = Arrays.copyOfRange(all, start, start + length);
        return sum(range);
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum;
    }

    private static void writeResults(Path output, String datasetName, int records, Config config,
                                     List<Result> results) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<Integer, Double> saltAverage = saltAverages(results);

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("dataset,records,method,access_mode,range_length,queries,warmup_queries,"
                    + "baseline_warmup_full_decompressions,"
                    + "total_time_ms,avg_us,p50_us,p95_us,p99_us,stddev_us,cv_percent,queries_per_sec,"
                    + "returned_values_per_sec,saltplus_speedup_over_method_x,checksum");
            writer.newLine();
            for (Result result : results) {
                double averageNs = result.averageNs();
                double totalNs = 0.0;
                for (long value : result.latencyNs) totalNs += value;
                double speedup = averageNs / saltAverage.get(result.rangeLength);
                writer.write(String.format(Locale.ROOT,
                        "%s,%d,%s,%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,"
                                + "%.3f,%.3f,%.6f,%.12g",
                        datasetName, records, result.method, result.accessMode, result.rangeLength,
                        config.queries, config.warmupQueries,
                        config.baselineWarmupFullDecompressions, totalNs / 1_000_000.0,
                        averageNs / 1_000.0, result.percentileNs(0.50) / 1_000.0,
                        result.percentileNs(0.95) / 1_000.0,
                        result.percentileNs(0.99) / 1_000.0,
                        result.standardDeviationNs() / 1_000.0,
                        result.standardDeviationNs() * 100.0 / averageNs,
                        1_000_000_000.0 / averageNs,
                        result.rangeLength * 1_000_000_000.0 / averageNs,
                        speedup, result.checksum));
                writer.newLine();
            }
        }
    }

    private static Map<Integer, Double> saltAverages(List<Result> results) {
        Map<Integer, Double> averages = new LinkedHashMap<Integer, Double>();
        for (Result result : results) {
            if ("SALT+".equals(result.method)) averages.put(result.rangeLength, result.averageNs());
        }
        return averages;
    }

    private static void printResults(List<Result> results) {
        Map<Integer, Double> saltAverage = saltAverages(results);
        System.out.printf(Locale.ROOT, "%-8s %-10s %12s %12s %9s %14s%n",
                "Length", "Method", "Avg (us)", "P95 (us)", "CV (%)", "SALT+ speedup");
        for (Result result : results) {
            System.out.printf(Locale.ROOT, "%-8d %-10s %12.3f %12.3f %9.2f %14.3fx%n",
                    result.rangeLength, result.method, result.averageNs() / 1_000.0,
                    result.percentileNs(0.95) / 1_000.0,
                    result.standardDeviationNs() * 100.0 / result.averageNs(),
                    result.averageNs() / saltAverage.get(result.rangeLength));
        }
    }
}
