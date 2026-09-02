package Experiment;

import SALT.SALTSQL_CRUD;
import algorithms.AlgorithmsManager;
import algorithms.Decoder;
import enums.DataTypeEnums;

import java.io.BufferedWriter;
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
 * Benchmarks SUM/AVG/MIN/MAX on randomly positioned ranges. SALT+ locates and
 * decodes only intersecting windows; compressed baselines fully decompress the
 * column for every aggregate query.
 */
public final class AggregateQueryBenchmark {
    private static final int[] DEFAULT_LENGTHS = {1024};
    private static final int DEFAULT_BASELINE_WARMUP_FULL_DECOMPRESSIONS = 120;
    private static volatile double blackhole;

    private AggregateQueryBenchmark() {
    }

    private enum Operation {
        SUM, AVG, MIN, MAX;

        double onRaw(double[] values, int start, int length) {
            double result = this == MIN ? Double.POSITIVE_INFINITY
                    : this == MAX ? Double.NEGATIVE_INFINITY : 0.0;
            int end = start + length;
            for (int i = start; i < end; i++) {
                double value = values[i];
                if (this == SUM || this == AVG) result += value;
                else if (this == MIN) result = Math.min(result, value);
                else result = Math.max(result, value);
            }
            return this == AVG ? result / length : result;
        }

        double onSaltPlus(SALTSQL_CRUD saltPlus, int start, int length) throws Exception {
            if (this == SUM) return saltPlus.sumRangeByRecordIndex(start, length);
            if (this == AVG) return saltPlus.avgRangeByRecordIndex(start, length);
            if (this == MIN) return saltPlus.minRangeByRecordIndex(start, length);
            return saltPlus.maxRangeByRecordIndex(start, length);
        }
    }

    private static final class Config {
        Path dataset = Paths.get("datasets", "Overall", "City-temp.csv");
        int column = 1;
        int queries = 100;
        int warmupQueries = 10;
        int baselineWarmupFullDecompressions = DEFAULT_BASELINE_WARMUP_FULL_DECOMPRESSIONS;
        long seed = 20260827L;
        Path storageRoot = Paths.get("storage", "aggregate_query");
        Path output = Paths.get("results", "aggregate_query", "aggregate_query_results.csv");
        List<Integer> lengths = toIntegerList(DEFAULT_LENGTHS);
        List<String> baselines = new ArrayList<String>(
                Arrays.asList(RangeQueryBenchmark.DEFAULT_BASELINES));
    }

    private static final class Result {
        final Operation operation;
        final String method;
        final String accessMode;
        final int rangeLength;
        final long[] latencyNs;
        final double checksum;

        Result(Operation operation, String method, String accessMode, int rangeLength,
               long[] latencyNs, double checksum) {
            this.operation = operation;
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
        RangeQueryBenchmark.Dataset dataset = RangeQueryBenchmark.readDataset(
                config.dataset, config.column);
        List<Integer> lengths = validLengths(config.lengths, dataset.doubles.length);
        if (lengths.isEmpty()) {
            throw new IllegalArgumentException("None of the requested lengths fits "
                    + dataset.doubles.length + " records");
        }

        String datasetName = RangeQueryBenchmark.removeExtension(
                config.dataset.getFileName().toString());
        System.out.println("[Setup] Dataset: " + config.dataset.toAbsolutePath());
        System.out.println("[Setup] Valid records: " + dataset.doubles.length);
        System.out.println("[Setup] Aggregate lengths: " + lengths);

        Path datasetStorage = config.storageRoot.resolve(datasetName);
        Path saltDirectory = datasetStorage.resolve("SALTPlus");
        Path baselineDirectory = datasetStorage.resolve("baselines");
        Files.createDirectories(saltDirectory);
        Files.createDirectories(baselineDirectory);

        String saltBase = saltDirectory.resolve(datasetName).toString();
        System.out.println("[Setup] Building SALT+ data and Fenwick indexes...");
        RangeQueryBenchmark.buildSaltPlusStore(dataset.decimals, saltBase);
        System.out.println("[Setup] SALT+ is ready.");

        Map<String, Path> baselineFiles = new LinkedHashMap<String, Path>();
        int prepared = 0;
        for (String method : config.baselines) {
            System.out.printf(Locale.ROOT, "[Setup %d/%d] Compressing and verifying %s...%n",
                    prepared + 1, config.baselines.size(), method);
            Path compressed = baselineDirectory.resolve(datasetName + "."
                    + method.toLowerCase(Locale.ROOT));
            RangeQueryBenchmark.compressBaseline(method, compressed, dataset.doubles);
            RangeQueryBenchmark.verifyBaseline(method, compressed, dataset.doubles);
            baselineFiles.put(method, compressed);
            prepared++;
            System.out.printf(Locale.ROOT, "[Setup %d/%d] %s is ready.%n",
                    prepared, config.baselines.size(), method);
        }

        Map<Integer, int[]> startsByLength = buildQueryStarts(
                dataset.doubles.length, lengths,
                config.queries + config.warmupQueries, config.seed);
        System.out.println("[Setup] All aggregate lengths share the same random start positions.");
        List<Result> results = new ArrayList<Result>();
        int totalTasks = Operation.values().length * lengths.size() * (2 + baselineFiles.size());
        int completed = 0;
        long benchmarkStartedNs;
        List<Map.Entry<String, Path>> baselineEntries =
                new ArrayList<Map.Entry<String, Path>>(baselineFiles.entrySet());

        try (SALTSQL_CRUD saltPlus = SALTSQL_CRUD.openByName(saltBase)) {
            if (saltPlus.getTotalRecords() != dataset.doubles.length) {
                throw new IllegalStateException("SALT+ record count mismatch");
            }
            System.out.println("[Setup] Prewarming SALT+ aggregate paths...");
            prewarmSaltPlusAggregates(saltPlus, startsByLength, lengths);
            benchmarkStartedNs = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "[Benchmark] Starting %d tasks: %d measured queries, %d fast-path warmups "
                            + "per configuration, %d fixed full-decompression warmups per baseline.%n",
                    totalTasks, config.queries, config.warmupQueries,
                    config.baselineWarmupFullDecompressions);

            System.out.println("[Benchmark] Phase 1/2: Raw and SALT+ (isolated from baseline GC).");
            for (Operation operation : Operation.values()) {
                for (int length : lengths) {
                    int[] starts = startsByLength.get(length);
                    int verificationStart = starts[config.warmupQueries];
                    verifySaltPlus(operation, saltPlus, dataset.doubles,
                            verificationStart, length);
                }
            }
            results.addAll(benchmarkFastMethodsRoundRobin(
                    dataset.doubles, saltPlus, startsByLength, lengths, config));
            completed += Operation.values().length * lengths.size() * 2;
            printProgress(completed, totalTasks, benchmarkStartedNs,
                    "all aggregate configurations, methods=Raw/SALT+");

            System.out.println("[Benchmark] Phase 2/2: original full-decompression baselines; "
                    + "all (operation, length) configurations are warmed first and measured round-robin.");
            for (Map.Entry<String, Path> baseline : baselineEntries) {
                results.addAll(benchmarkBaselineRoundRobin(
                        baseline.getKey(), baseline.getValue(), dataset.doubles.length,
                        startsByLength, lengths, config));
                completed += Operation.values().length * lengths.size();
                printProgress(completed, totalTasks, benchmarkStartedNs,
                        "all aggregate configurations, method=" + baseline.getKey());
            }
        }

        Collections.sort(results, new Comparator<Result>() {
            @Override
            public int compare(Result left, Result right) {
                int byOperation = left.operation.compareTo(right.operation);
                if (byOperation != 0) return byOperation;
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
            else if ("--lengths".equals(arg)) config.lengths = parseLengths(requireValue(args, ++i, arg));
            else if ("--baselines".equals(arg)) {
                config.baselines.clear();
                String[] names = requireValue(args, ++i, arg).split(",");
                for (String name : names) if (!name.trim().isEmpty()) config.baselines.add(name.trim());
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
        if (config.lengths.isEmpty()) throw new IllegalArgumentException("--lengths cannot be empty");
        if (config.baselines.isEmpty()) throw new IllegalArgumentException("--baselines cannot be empty");
        return config;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value after " + option);
        return args[index];
    }

    private static List<Integer> parseLengths(String text) {
        List<Integer> lengths = new ArrayList<Integer>();
        for (String token : text.split(",")) {
            int value = Integer.parseInt(token.trim());
            if (value <= 0) throw new IllegalArgumentException("Aggregate lengths must be > 0");
            if (!lengths.contains(value)) lengths.add(value);
        }
        return lengths;
    }

    private static List<Integer> toIntegerList(int[] values) {
        List<Integer> result = new ArrayList<Integer>();
        for (int value : values) result.add(value);
        return result;
    }

    private static List<Integer> validLengths(List<Integer> requested, int records) {
        List<Integer> valid = new ArrayList<Integer>();
        for (int length : requested) {
            if (length <= records) valid.add(length);
            else System.out.println("[Setup] Skipping range length " + length
                    + " because the dataset contains only " + records + " values.");
        }
        return valid;
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java Experiment.AggregateQueryBenchmark [options]");
        System.out.println("  --dataset <csv>       default: datasets/Overall/Wind-Speed.csv");
        System.out.println("  --column <zero-based> default: 1");
        System.out.println("  --lengths <csv>       default: 16,128,256,1024,4096,16384,65536");
        System.out.println("  --queries <count>     default: 100 per operation and range length");
        System.out.println("  --warmup <count>      default: 10");
        System.out.println("  --baseline-warmup <n> fixed full decompressions per baseline; default: 120");
        System.out.println("  --seed <long>         default: 20260827");
        System.out.println("  --baselines <csv>     default: all current paper baselines plus SALTE");
        System.out.println("  --storage <dir>       default: storage/aggregate_query");
        System.out.println("  --output <csv>        default: results/aggregate_query/aggregate_query_results.csv");
        System.exit(0);
    }

    private static Map<Integer, int[]> buildQueryStarts(int records, List<Integer> lengths,
                                                        int count, long seed) {
        Map<Integer, int[]> result = new LinkedHashMap<Integer, int[]>();
        int maximumLength = 0;
        for (int length : lengths) maximumLength = Math.max(maximumLength, length);
        int bound = records - maximumLength + 1;
        if (bound <= 0) throw new IllegalArgumentException("Dataset is shorter than maximum length");
        Random random = new Random(seed);
        int[] commonStarts = new int[count];
        for (int i = 0; i < count; i++) commonStarts[i] = random.nextInt(bound);
        for (int length : lengths) {
            result.put(length, commonStarts.clone());
        }
        return result;
    }

    private static void prewarmSaltPlusAggregates(SALTSQL_CRUD saltPlus,
                                                   Map<Integer, int[]> startsByLength,
                                                   List<Integer> lengths) throws Exception {
        double checksum = 0.0;
        for (int round = 0; round < 10; round++) {
            for (Operation operation : Operation.values()) {
                for (int index = lengths.size() - 1; index >= 0; index--) {
                    int length = lengths.get(index);
                    int[] starts = startsByLength.get(length);
                    checksum += operation.onSaltPlus(
                            saltPlus, starts[round % starts.length], length);
                }
            }
        }
        blackhole += checksum;
    }

    private static void verifySaltPlus(Operation operation, SALTSQL_CRUD saltPlus,
                                       double[] expectedValues, int start, int length) throws Exception {
        double expected = operation.onRaw(expectedValues, start, length);
        double actual = operation.onSaltPlus(saltPlus, start, length);
        if (!sameAggregate(expected, actual)) {
            throw new IllegalStateException("SALT+ " + operation + " correctness failed: start="
                    + start + ", length=" + length + ", expected=" + expected + ", actual=" + actual);
        }
    }

    private static boolean sameAggregate(double expected, double actual) {
        if (Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual)) return true;
        double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        return Math.abs(expected - actual) <= 1e-12 * scale;
    }

    private static List<Result> benchmarkFastMethodsRoundRobin(
            double[] values, SALTSQL_CRUD saltPlus, Map<Integer, int[]> startsByLength,
            List<Integer> lengths, Config config) throws Exception {
        Map<String, long[]> rawLatency = newAggregateLatencyMap(lengths, config.queries);
        Map<String, long[]> saltLatency = newAggregateLatencyMap(lengths, config.queries);
        Map<String, Double> rawChecksums = newAggregateChecksumMap(lengths);
        Map<String, Double> saltChecksums = newAggregateChecksumMap(lengths);
        int configurationCount = Operation.values().length * lengths.size();

        // Warm every (operation, length) configuration before recording latency.
        for (int round = 0; round < config.warmupQueries; round++) {
            int rotation = round % configurationCount;
            for (int offset = 0; offset < configurationCount; offset++) {
                int configuration = (rotation + offset) % configurationCount;
                Operation operation = operationAt(configuration, lengths.size());
                int length = lengthAt(configuration, lengths);
                String resultKey = key(operation, length);
                int start = startsByLength.get(length)[round];
                rawChecksums.put(resultKey, rawChecksums.get(resultKey)
                        + operation.onRaw(values, start, length));
                saltChecksums.put(resultKey, saltChecksums.get(resultKey)
                        + operation.onSaltPlus(saltPlus, start, length));
            }
        }

        for (int sample = 0; sample < config.queries; sample++) {
            int startIndex = config.warmupQueries + sample;
            int rotation = startIndex % configurationCount;
            for (int offset = 0; offset < configurationCount; offset++) {
                int configuration = (rotation + offset) % configurationCount;
                Operation operation = operationAt(configuration, lengths.size());
                int length = lengthAt(configuration, lengths);
                String resultKey = key(operation, length);
                int start = startsByLength.get(length)[startIndex];

                long rawBegin = System.nanoTime();
                double rawChecksum = operation.onRaw(values, start, length);
                rawLatency.get(resultKey)[sample] = System.nanoTime() - rawBegin;
                rawChecksums.put(resultKey, rawChecksums.get(resultKey) + rawChecksum);

                long saltBegin = System.nanoTime();
                double saltChecksum = operation.onSaltPlus(saltPlus, start, length);
                saltLatency.get(resultKey)[sample] = System.nanoTime() - saltBegin;
                saltChecksums.put(resultKey, saltChecksums.get(resultKey) + saltChecksum);
            }
            if ((sample + 1) % 10 == 0 || sample + 1 == config.queries) {
                System.out.printf(Locale.ROOT,
                        "[Raw/SALT+] measured aggregate round %d/%d%n",
                        sample + 1, config.queries);
            }
        }

        List<Result> results = new ArrayList<Result>();
        for (Operation operation : Operation.values()) {
            for (int length : lengths) {
                String resultKey = key(operation, length);
                double rawChecksum = rawChecksums.get(resultKey);
                double saltChecksum = saltChecksums.get(resultKey);
                results.add(new Result(operation, "Raw", "raw_scan", length,
                        rawLatency.get(resultKey), rawChecksum));
                results.add(new Result(operation, "SALT+", "indexed_local_aggregation", length,
                        saltLatency.get(resultKey), saltChecksum));
                blackhole += rawChecksum + saltChecksum;
            }
        }
        return results;
    }

    private static List<Result> benchmarkBaselineRoundRobin(
            String method, Path compressed, int records,
            Map<Integer, int[]> startsByLength, List<Integer> lengths,
            Config config) throws Exception {
        Map<String, long[]> latencyByConfiguration =
                newAggregateLatencyMap(lengths, config.queries);
        Map<String, Double> checksums = newAggregateChecksumMap(lengths);
        int configurationCount = Operation.values().length * lengths.size();

        // Keep decoder/JIT warmup independent of the number of operations and lengths.
        System.out.printf(Locale.ROOT, "[%s] Prewarming with %d full decompressions.%n",
                method, config.baselineWarmupFullDecompressions);
        for (int warmup = 0; warmup < config.baselineWarmupFullDecompressions; warmup++) {
            int configuration = warmup % configurationCount;
            Operation operation = operationAt(configuration, lengths.size());
            int length = lengthAt(configuration, lengths);
            String resultKey = key(operation, length);
            int[] starts = startsByLength.get(length);
            int start = starts[warmup % starts.length];
            double checksum = fullDecompressAndAggregate(
                    operation, method, compressed, records, start, length);
            checksums.put(resultKey, checksums.get(resultKey) + checksum);
        }

        for (int sample = 0; sample < config.queries; sample++) {
            int startIndex = config.warmupQueries + sample;
            int rotation = startIndex % configurationCount;
            for (int offset = 0; offset < configurationCount; offset++) {
                int configuration = (rotation + offset) % configurationCount;
                Operation operation = operationAt(configuration, lengths.size());
                int length = lengthAt(configuration, lengths);
                String resultKey = key(operation, length);
                int start = startsByLength.get(length)[startIndex];
                long begin = System.nanoTime();
                double checksum = fullDecompressAndAggregate(
                        operation, method, compressed, records, start, length);
                latencyByConfiguration.get(resultKey)[sample] = System.nanoTime() - begin;
                checksums.put(resultKey, checksums.get(resultKey) + checksum);
            }
            if ((sample + 1) % 10 == 0 || sample + 1 == config.queries) {
                System.out.printf(Locale.ROOT,
                        "[%s] measured aggregate round %d/%d%n",
                        method, sample + 1, config.queries);
            }
        }

        List<Result> results = new ArrayList<Result>();
        for (Operation operation : Operation.values()) {
            for (int length : lengths) {
                String resultKey = key(operation, length);
                double checksum = checksums.get(resultKey);
                results.add(new Result(operation, method, "full_decompression", length,
                        latencyByConfiguration.get(resultKey), checksum));
                blackhole += checksum;
            }
        }
        return results;
    }

    private static Operation operationAt(int configuration, int lengthCount) {
        return Operation.values()[configuration / lengthCount];
    }

    private static int lengthAt(int configuration, List<Integer> lengths) {
        return lengths.get(configuration % lengths.size());
    }

    private static Map<String, long[]> newAggregateLatencyMap(
            List<Integer> lengths, int samples) {
        Map<String, long[]> values = new LinkedHashMap<String, long[]>();
        for (Operation operation : Operation.values()) {
            for (int length : lengths) values.put(key(operation, length), new long[samples]);
        }
        return values;
    }

    private static Map<String, Double> newAggregateChecksumMap(List<Integer> lengths) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        for (Operation operation : Operation.values()) {
            for (int length : lengths) values.put(key(operation, length), 0.0);
        }
        return values;
    }

    private static double fullDecompressAndAggregate(Operation operation, String method,
                                                     Path compressed, int records,
                                                     int start, int length) throws Exception {
        Decoder decoder = AlgorithmsManager.getDecoder(
                DataTypeEnums.DOUBLE.getType(), method, compressed.toString());
        double[] values = new double[records];
        for (int i = 0; i < records; i++) values[i] = decoder.decodeDouble();
        return operation.onRaw(values, start, length);
    }

    private static void printProgress(int completed, int total, long startedNs, String task) {
        long elapsedNs = System.nanoTime() - startedNs;
        double percent = 100.0 * completed / total;
        long estimatedTotalNs = (long) ((double) elapsedNs * total / completed);
        long remainingNs = Math.max(0L, estimatedTotalNs - elapsedNs);
        System.out.printf(Locale.ROOT,
                "[Benchmark %d/%d, %5.1f%%] Finished %s | elapsed %s | ETA %s%n",
                completed, total, percent, task,
                formatDuration(elapsedNs), formatDuration(remainingNs));
    }

    private static String formatDuration(long nanoseconds) {
        long totalSeconds = Math.max(0L, nanoseconds / 1_000_000_000L);
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String key(Operation operation, int length) {
        return operation.name() + ':' + length;
    }

    private static Map<String, Double> saltAverages(List<Result> results) {
        Map<String, Double> averages = new LinkedHashMap<String, Double>();
        for (Result result : results) {
            if ("SALT+".equals(result.method)) {
                averages.put(key(result.operation, result.rangeLength), result.averageNs());
            }
        }
        return averages;
    }

    private static void writeResults(Path output, String datasetName, int records,
                                     Config config, List<Result> results) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Double> saltAverage = saltAverages(results);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("dataset,records,operation,method,access_mode,range_length,queries,"
                    + "warmup_queries,baseline_warmup_full_decompressions,total_time_ms,avg_us,p50_us,p95_us,p99_us,stddev_us,cv_percent,queries_per_sec,"
                    + "saltplus_speedup_over_method_x,checksum");
            writer.newLine();
            for (Result result : results) {
                double totalNs = 0.0;
                for (long latency : result.latencyNs) totalNs += latency;
                double averageNs = result.averageNs();
                double speedup = averageNs / saltAverage.get(key(result.operation, result.rangeLength));
                writer.write(String.format(Locale.ROOT,
                        "%s,%d,%s,%s,%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,%.3f,%.6f,%.12g",
                        datasetName, records, result.operation, result.method, result.accessMode,
                        result.rangeLength, config.queries, config.warmupQueries,
                        config.baselineWarmupFullDecompressions,
                        totalNs / 1_000_000.0, averageNs / 1_000.0,
                        result.percentileNs(0.50) / 1_000.0,
                        result.percentileNs(0.95) / 1_000.0,
                        result.percentileNs(0.99) / 1_000.0,
                        result.standardDeviationNs() / 1_000.0,
                        result.standardDeviationNs() * 100.0 / averageNs,
                        1_000_000_000.0 / averageNs, speedup, result.checksum));
                writer.newLine();
            }
        }
    }

    private static void printResults(List<Result> results) {
        Map<String, Double> saltAverage = saltAverages(results);
        System.out.printf(Locale.ROOT, "%-5s %-8s %-10s %12s %12s %9s %14s%n",
                "Op", "Length", "Method", "Avg (us)", "P95 (us)", "CV (%)", "SALT+ speedup");
        for (Result result : results) {
            double speedup = result.averageNs()
                    / saltAverage.get(key(result.operation, result.rangeLength));
            System.out.printf(Locale.ROOT, "%-5s %-8d %-10s %12.3f %12.3f %9.2f %14.3fx%n",
                    result.operation, result.rangeLength, result.method,
                    result.averageNs() / 1_000.0,
                    result.percentileNs(0.95) / 1_000.0,
                    result.standardDeviationNs() * 100.0 / result.averageNs(), speedup);
        }
    }
}
