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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * Benchmarks complete window-aggregation operators over a column. Raw scans the
 * uncompressed values once, compressed baselines fully decompress once and then
 * run the operator, while SALT+ decodes indexed windows sequentially without
 * materializing the complete column.
 */
public final class WindowAggregationBenchmark {
    private static final int[] DEFAULT_WINDOWS = {16, 128, 256, 1024, 4096};
    private static volatile double blackhole;

    private WindowAggregationBenchmark() {
    }

    private enum Operation {
        SUM, AVG, MIN, MAX
    }

    private static final class Config {
        Path dataset = Paths.get("datasets", "Overall", "Wind-Speed.csv");
        int column = 1;
        int trials = 10;
        int warmupTrials = 2;
        Path storageRoot = Paths.get("storage", "window_aggregation");
        Path output = Paths.get("results", "window_aggregation",
                "window_aggregation_results.csv");
        List<Integer> windows = toIntegerList(DEFAULT_WINDOWS);
        List<String> stepExpressions = new ArrayList<String>(
                Arrays.asList("1", "W/4", "W"));
        List<String> baselines = new ArrayList<String>(
                Arrays.asList(RangeQueryBenchmark.DEFAULT_BASELINES));
    }

    private static final class WindowRun {
        final long outputWindows;
        final double checksum;
        final long hash;

        WindowRun(long outputWindows, double checksum, long hash) {
            this.outputWindows = outputWindows;
            this.checksum = checksum;
            this.hash = hash;
        }
    }

    /** Primitive rolling implementation shared by every method. */
    private static final class WindowAccumulator implements DoubleConsumer {
        private final Operation operation;
        private final int windowSize;
        private final int step;
        private final double[] ring;

        private final long[] dequeIndices;
        private final double[] dequeValues;
        private int dequeHead;
        private int dequeTail;

        private long seen;
        private long outputWindows;
        private double rollingSum;
        private double checksum;
        private long hash = 1125899906842597L;

        WindowAccumulator(Operation operation, int windowSize, int step) {
            this.operation = operation;
            this.windowSize = windowSize;
            this.step = step;
            this.ring = operation == Operation.SUM || operation == Operation.AVG
                    ? new double[windowSize] : null;
            this.dequeIndices = operation == Operation.MIN || operation == Operation.MAX
                    ? new long[windowSize + 1] : null;
            this.dequeValues = operation == Operation.MIN || operation == Operation.MAX
                    ? new double[windowSize + 1] : null;
        }

        @Override
        public void accept(double value) {
            long index = seen;
            if (operation == Operation.SUM || operation == Operation.AVG) {
                int slot = (int) (index % windowSize);
                if (index >= windowSize) rollingSum -= ring[slot];
                ring[slot] = value;
                rollingSum += value;
            } else {
                long firstValidIndex = index - windowSize + 1L;
                while (!dequeEmpty() && dequeIndices[dequeHead] < firstValidIndex) {
                    dequeHead = nextDequePosition(dequeHead);
                }
                while (!dequeEmpty()) {
                    int last = previousDequePosition(dequeTail);
                    boolean discard = operation == Operation.MIN
                            ? dequeValues[last] >= value : dequeValues[last] <= value;
                    if (!discard) break;
                    dequeTail = last;
                }
                dequeIndices[dequeTail] = index;
                dequeValues[dequeTail] = value;
                dequeTail = nextDequePosition(dequeTail);
            }

            seen++;
            if (seen >= windowSize) {
                long windowStart = seen - windowSize;
                if (windowStart % step == 0) emitCurrentWindow();
            }
        }

        private void emitCurrentWindow() {
            double result;
            if (operation == Operation.SUM) result = rollingSum;
            else if (operation == Operation.AVG) result = rollingSum / windowSize;
            else result = dequeValues[dequeHead];
            checksum += result;
            hash = 31L * hash + Double.doubleToLongBits(result);
            outputWindows++;
        }

        private boolean dequeEmpty() {
            return dequeHead == dequeTail;
        }

        private int nextDequePosition(int position) {
            position++;
            return position == dequeIndices.length ? 0 : position;
        }

        private int previousDequePosition(int position) {
            return position == 0 ? dequeIndices.length - 1 : position - 1;
        }

        WindowRun finish() {
            return new WindowRun(outputWindows, checksum, hash);
        }
    }

    private static final class Result {
        final Operation operation;
        final int windowSize;
        final int step;
        final String method;
        final String accessMode;
        final long outputWindows;
        final long[] latencyNs;
        final double checksum;

        Result(Operation operation, int windowSize, int step, String method,
               String accessMode, long outputWindows, long[] latencyNs, double checksum) {
            this.operation = operation;
            this.windowSize = windowSize;
            this.step = step;
            this.method = method;
            this.accessMode = accessMode;
            this.outputWindows = outputWindows;
            this.latencyNs = latencyNs;
            this.checksum = checksum;
        }

        double averageNs() {
            long total = 0L;
            for (long latency : latencyNs) total += latency;
            return (double) total / latencyNs.length;
        }

        double percentileNs(double percentile) {
            long[] sorted = latencyNs.clone();
            Arrays.sort(sorted);
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            if (index < 0) index = 0;
            if (index >= sorted.length) index = sorted.length - 1;
            return sorted[index];
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);
        RangeQueryBenchmark.Dataset dataset = RangeQueryBenchmark.readDataset(
                config.dataset, config.column);
        List<Integer> windows = validWindows(config.windows, dataset.doubles.length);
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("None of the requested windows fits "
                    + dataset.doubles.length + " records");
        }

        String datasetName = RangeQueryBenchmark.removeExtension(
                config.dataset.getFileName().toString());
        System.out.println("[Setup] Dataset: " + config.dataset.toAbsolutePath());
        System.out.println("[Setup] Valid records: " + dataset.doubles.length);
        System.out.println("[Setup] Window sizes: " + windows);
        for (int window : windows) {
            System.out.println("[Setup] W=" + window + ", steps="
                    + resolveSteps(config.stepExpressions, window));
        }

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

        System.out.println("[Setup] Prewarming rolling aggregation kernels...");
        prewarm(dataset.doubles);

        int configurations = 0;
        for (int window : windows) {
            configurations += resolveSteps(config.stepExpressions, window).size();
        }
        int totalTasks = Operation.values().length * configurations * (2 + baselineFiles.size());
        int completed = 0;
        long benchmarkStartedNs = System.nanoTime();
        List<Result> results = new ArrayList<Result>();
        System.out.printf(Locale.ROOT,
                "[Benchmark] Starting %d tasks: %d measured runs + %d warmups per task.%n",
                totalTasks, config.trials, config.warmupTrials);

        try (SALTSQL_CRUD saltPlus = SALTSQL_CRUD.openByName(saltBase)) {
            if (saltPlus.getTotalRecords() != dataset.doubles.length) {
                throw new IllegalStateException("SALT+ record count mismatch");
            }
            for (Operation operation : Operation.values()) {
                for (int window : windows) {
                    for (int step : resolveSteps(config.stepExpressions, window)) {
                        String configLabel = operation + ", W=" + window + ", step=" + step;
                        System.out.println("[Benchmark] " + configLabel);
                        verifySaltPlus(operation, window, step, dataset.doubles, saltPlus);

                        results.add(benchmarkRaw(operation, window, step,
                                dataset.doubles, config));
                        completed++;
                        printProgress(completed, totalTasks, benchmarkStartedNs,
                                configLabel + ", method=Raw");

                        results.add(benchmarkSaltPlus(operation, window, step,
                                dataset.doubles.length, saltPlus, config));
                        completed++;
                        printProgress(completed, totalTasks, benchmarkStartedNs,
                                configLabel + ", method=SALT+");

                        for (Map.Entry<String, Path> baseline : baselineFiles.entrySet()) {
                            results.add(benchmarkFullDecompression(operation, window, step,
                                    baseline.getKey(), baseline.getValue(),
                                    dataset.doubles.length, config));
                            completed++;
                            printProgress(completed, totalTasks, benchmarkStartedNs,
                                    configLabel + ", method=" + baseline.getKey());
                        }
                    }
                }
            }
        }

        Collections.sort(results, new Comparator<Result>() {
            @Override
            public int compare(Result left, Result right) {
                int comparison = left.operation.compareTo(right.operation);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(left.windowSize, right.windowSize);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(left.step, right.step);
                if (comparison != 0) return comparison;
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
            else if ("--trials".equals(arg)) config.trials = Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--warmup".equals(arg)) config.warmupTrials = Integer.parseInt(requireValue(args, ++i, arg));
            else if ("--storage".equals(arg)) config.storageRoot = Paths.get(requireValue(args, ++i, arg));
            else if ("--output".equals(arg)) config.output = Paths.get(requireValue(args, ++i, arg));
            else if ("--windows".equals(arg)) config.windows = parsePositiveIntegers(
                    requireValue(args, ++i, arg), "--windows");
            else if ("--steps".equals(arg)) config.stepExpressions = parseStepExpressions(
                    requireValue(args, ++i, arg));
            else if ("--baselines".equals(arg)) {
                config.baselines.clear();
                for (String name : requireValue(args, ++i, arg).split(",")) {
                    if (!name.trim().isEmpty()) config.baselines.add(name.trim());
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsageAndExit();
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        if (config.column < 0) throw new IllegalArgumentException("--column must be >= 0");
        if (config.trials <= 0) throw new IllegalArgumentException("--trials must be > 0");
        if (config.warmupTrials < 0) throw new IllegalArgumentException("--warmup must be >= 0");
        if (config.windows.isEmpty()) throw new IllegalArgumentException("--windows cannot be empty");
        if (config.stepExpressions.isEmpty()) throw new IllegalArgumentException("--steps cannot be empty");
        if (config.baselines.isEmpty()) throw new IllegalArgumentException("--baselines cannot be empty");
        return config;
    }

    private static String requireValue(String[] args, int index, String argument) {
        if (index >= args.length) throw new IllegalArgumentException(argument + " requires a value");
        return args[index];
    }

    private static List<Integer> parsePositiveIntegers(String value, String argument) {
        List<Integer> parsed = new ArrayList<Integer>();
        for (String token : value.split(",")) {
            if (token.trim().isEmpty()) continue;
            int number = Integer.parseInt(token.trim());
            if (number <= 0) throw new IllegalArgumentException(argument + " values must be > 0");
            if (!parsed.contains(number)) parsed.add(number);
        }
        return parsed;
    }

    private static List<String> parseStepExpressions(String value) {
        List<String> expressions = new ArrayList<String>();
        for (String token : value.split(",")) {
            String expression = token.trim().toUpperCase(Locale.ROOT);
            if (expression.isEmpty()) continue;
            if (!("W".equals(expression) || "W/2".equals(expression)
                    || "W/4".equals(expression))) {
                int numeric = Integer.parseInt(expression);
                if (numeric <= 0) throw new IllegalArgumentException("--steps values must be > 0");
            }
            if (!expressions.contains(expression)) expressions.add(expression);
        }
        return expressions;
    }

    private static List<Integer> resolveSteps(List<String> expressions, int window) {
        Set<Integer> resolved = new LinkedHashSet<Integer>();
        for (String expression : expressions) {
            if ("W".equals(expression)) resolved.add(window);
            else if ("W/2".equals(expression)) resolved.add(Math.max(1, window / 2));
            else if ("W/4".equals(expression)) resolved.add(Math.max(1, window / 4));
            else resolved.add(Integer.parseInt(expression));
        }
        return new ArrayList<Integer>(resolved);
    }

    private static List<Integer> validWindows(List<Integer> requested, int records) {
        List<Integer> valid = new ArrayList<Integer>();
        for (int window : requested) {
            if (window <= records) valid.add(window);
            else System.out.println("[Setup] Skipping W=" + window
                    + " because the dataset has only " + records + " records.");
        }
        return valid;
    }

    private static List<Integer> toIntegerList(int[] values) {
        List<Integer> result = new ArrayList<Integer>();
        for (int value : values) result.add(value);
        return result;
    }

    private static void prewarm(double[] values) {
        int count = Math.min(values.length, 4096);
        if (count < 16) return;
        for (int repeat = 0; repeat < 20; repeat++) {
            for (Operation operation : Operation.values()) {
                blackhole += runArray(values, count, operation, 16, 1).checksum;
            }
        }
    }

    private static WindowRun runArray(double[] values, int count, Operation operation,
                                      int window, int step) {
        WindowAccumulator accumulator = new WindowAccumulator(operation, window, step);
        for (int i = 0; i < count; i++) accumulator.accept(values[i]);
        return accumulator.finish();
    }

    private static WindowRun runSaltPlus(SALTSQL_CRUD saltPlus, int records,
                                         Operation operation, int window, int step)
            throws Exception {
        WindowAccumulator accumulator = new WindowAccumulator(operation, window, step);
        saltPlus.scanRangeByRecordIndex(0, records, accumulator);
        return accumulator.finish();
    }

    private static WindowRun fullDecompressAndAggregate(String method, Path compressed,
                                                        int records, Operation operation,
                                                        int window, int step) throws Exception {
        double[] values = new double[records];
        Decoder decoder = AlgorithmsManager.getDecoder(
                DataTypeEnums.DOUBLE.getType(), method, compressed.toString());
        for (int i = 0; i < records; i++) values[i] = decoder.decodeDouble();
        return runArray(values, records, operation, window, step);
    }

    private static void verifySaltPlus(Operation operation, int window, int step,
                                       double[] values, SALTSQL_CRUD saltPlus) throws Exception {
        WindowRun expected = runArray(values, values.length, operation, window, step);
        WindowRun actual = runSaltPlus(saltPlus, values.length, operation, window, step);
        if (expected.outputWindows != actual.outputWindows || expected.hash != actual.hash) {
            throw new IllegalStateException("SALT+ window aggregation mismatch for " + operation
                    + ", W=" + window + ", step=" + step + ": expected windows/hash="
                    + expected.outputWindows + "/" + expected.hash + ", actual="
                    + actual.outputWindows + "/" + actual.hash);
        }
    }

    private static Result benchmarkRaw(Operation operation, int window, int step,
                                       double[] values, Config config) {
        double checksum = 0.0;
        for (int i = 0; i < config.warmupTrials; i++) {
            checksum += runArray(values, values.length, operation, window, step).checksum;
        }
        long[] latency = new long[config.trials];
        long outputWindows = 0L;
        for (int i = 0; i < config.trials; i++) {
            long begin = System.nanoTime();
            WindowRun run = runArray(values, values.length, operation, window, step);
            latency[i] = System.nanoTime() - begin;
            outputWindows = run.outputWindows;
            checksum += run.checksum;
        }
        blackhole += checksum;
        return new Result(operation, window, step, "Raw", "raw_single_scan",
                outputWindows, latency, checksum);
    }

    private static Result benchmarkSaltPlus(Operation operation, int window, int step,
                                            int records, SALTSQL_CRUD saltPlus, Config config)
            throws Exception {
        double checksum = 0.0;
        for (int i = 0; i < config.warmupTrials; i++) {
            checksum += runSaltPlus(saltPlus, records, operation, window, step).checksum;
        }
        long[] latency = new long[config.trials];
        long outputWindows = 0L;
        for (int i = 0; i < config.trials; i++) {
            long begin = System.nanoTime();
            WindowRun run = runSaltPlus(saltPlus, records, operation, window, step);
            latency[i] = System.nanoTime() - begin;
            outputWindows = run.outputWindows;
            checksum += run.checksum;
        }
        blackhole += checksum;
        return new Result(operation, window, step, "SALT+", "indexed_sequential_decode",
                outputWindows, latency, checksum);
    }

    private static Result benchmarkFullDecompression(Operation operation, int window, int step,
                                                      String method, Path compressed, int records,
                                                      Config config) throws Exception {
        double checksum = 0.0;
        for (int i = 0; i < config.warmupTrials; i++) {
            checksum += fullDecompressAndAggregate(method, compressed, records,
                    operation, window, step).checksum;
        }
        long[] latency = new long[config.trials];
        long outputWindows = 0L;
        for (int i = 0; i < config.trials; i++) {
            long begin = System.nanoTime();
            WindowRun run = fullDecompressAndAggregate(method, compressed, records,
                    operation, window, step);
            latency[i] = System.nanoTime() - begin;
            outputWindows = run.outputWindows;
            checksum += run.checksum;
        }
        blackhole += checksum;
        return new Result(operation, window, step, method,
                "full_decompression_then_single_scan", outputWindows, latency, checksum);
    }

    private static void printProgress(int completed, int total, long startedNs,
                                      String finishedTask) {
        long elapsedNs = System.nanoTime() - startedNs;
        double elapsedSeconds = elapsedNs / 1_000_000_000.0;
        double remainingSeconds = completed == 0 ? 0.0
                : elapsedSeconds * (total - completed) / completed;
        double percent = 100.0 * completed / total;
        System.out.printf(Locale.ROOT,
                "[Benchmark %d/%d, %.1f%%] Finished %s | elapsed %s | ETA %s%n",
                completed, total, percent, finishedTask,
                formatDuration(elapsedSeconds), formatDuration(remainingSeconds));
    }

    private static String formatDuration(double seconds) {
        long rounded = Math.max(0L, Math.round(seconds));
        long hours = rounded / 3600;
        long minutes = (rounded % 3600) / 60;
        long remainder = rounded % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%dm%02ds", minutes, remainder);
    }

    private static void writeResults(Path output, String datasetName, int records,
                                     Config config, List<Result> results) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Double> saltAverages = saltAverages(results);

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("dataset,records,operation,window_size,step,output_windows,method,access_mode,"
                    + "trials,warmup_trials,total_time_ms,avg_ms,p50_ms,p95_ms,p99_ms,"
                    + "windows_per_sec,input_values_per_sec,saltplus_speedup_over_method_x,checksum");
            writer.newLine();
            for (Result result : results) {
                double averageNs = result.averageNs();
                double totalNs = 0.0;
                for (long latency : result.latencyNs) totalNs += latency;
                double speedup = averageNs / saltAverages.get(key(result));
                writer.write(String.format(Locale.ROOT,
                        "%s,%d,%s,%d,%d,%d,%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,"
                                + "%.3f,%.3f,%.6f,%.12g",
                        datasetName, records, result.operation, result.windowSize, result.step,
                        result.outputWindows, result.method, result.accessMode,
                        config.trials, config.warmupTrials, totalNs / 1_000_000.0,
                        averageNs / 1_000_000.0, result.percentileNs(0.50) / 1_000_000.0,
                        result.percentileNs(0.95) / 1_000_000.0,
                        result.percentileNs(0.99) / 1_000_000.0,
                        result.outputWindows * 1_000_000_000.0 / averageNs,
                        records * 1_000_000_000.0 / averageNs,
                        speedup, result.checksum));
                writer.newLine();
            }
        }
    }

    private static Map<String, Double> saltAverages(List<Result> results) {
        Map<String, Double> averages = new LinkedHashMap<String, Double>();
        for (Result result : results) {
            if ("SALT+".equals(result.method)) averages.put(key(result), result.averageNs());
        }
        return averages;
    }

    private static String key(Result result) {
        return result.operation + ":" + result.windowSize + ":" + result.step;
    }

    private static void printResults(List<Result> results) {
        Map<String, Double> saltAverages = saltAverages(results);
        System.out.println();
        System.out.printf(Locale.ROOT,
                "%-5s %7s %7s %-12s %12s %12s %14s %15s%n",
                "Op", "Window", "Step", "Method", "Avg (ms)", "P95 (ms)",
                "Windows/sec", "SALT+ speedup");
        for (Result result : results) {
            double averageNs = result.averageNs();
            System.out.printf(Locale.ROOT,
                    "%-5s %7d %7d %-12s %12.3f %12.3f %14.1f %14.3fx%n",
                    result.operation, result.windowSize, result.step, result.method,
                    averageNs / 1_000_000.0, result.percentileNs(0.95) / 1_000_000.0,
                    result.outputWindows * 1_000_000_000.0 / averageNs,
                    averageNs / saltAverages.get(key(result)));
        }
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java -cp target/classes Experiment.WindowAggregationBenchmark [options]");
        System.out.println("  --dataset <csv>       Input CSV (default: datasets/Overall/Wind-Speed.csv)");
        System.out.println("  --column <index>      Zero-based numeric column (default: 1)");
        System.out.println("  --windows <csv>       Window sizes (default: 16,128,256,1024,4096)");
        System.out.println("  --steps <csv>         Steps: integers, W/4, W/2, or W (default: 1,W/4,W)");
        System.out.println("  --trials <count>      Measured complete operator runs (default: 10)");
        System.out.println("  --warmup <count>      Warmup complete operator runs (default: 2)");
        System.out.println("  --baselines <csv>     Baseline method names");
        System.out.println("  --storage <dir>       Compressed working files directory");
        System.out.println("  --output <csv>        Result CSV path");
        System.exit(0);
    }
}
