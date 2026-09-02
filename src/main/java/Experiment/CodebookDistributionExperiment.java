package Experiment;

import algorithms.SALTE.SALTEUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Measures how concentrated SALT's (sign, deltaUlp, deltaExponent) symbols are.
 *
 * <p>The symbol selection deliberately uses the encoder's warm-up cost model for
 * every value.  Therefore the measured concentration is not caused by a codebook
 * already being present.  Each CSV file is treated as an independent time series
 * and resets the four reference windows.</p>
 *
 * <p>Example:</p>
 * <pre>
 * java Experiment.CodebookDistributionExperiment datasets/Overall results/codebook_distribution
 * </pre>
 */
public final class CodebookDistributionExperiment {
    private static final int WIN_BITS = 2;
    private static final int EXPONENT_BITS = 3;
    private static final int ULP_BITS = 3;
    private static final int CODEBOOK_EXP_BITS = 11;
    private static final int BLOCK_SIZE = 30000;
    private static final int VALUE_COLUMN = 1;
    private static final int[] TOP_K = {1, 2, 4, 8, 16, 32, 64, 128, 256};
    private static final double[] REGION_TARGETS = {0.90, 0.95, 0.99};

    private CodebookDistributionExperiment() {}

    private static final class Key {
        final int sign;
        final int deltaUlp;
        final int deltaExponent;

        Key(int sign, int deltaUlp, int deltaExponent) {
            this.sign = sign;
            this.deltaUlp = deltaUlp;
            this.deltaExponent = deltaExponent;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key k = (Key) other;
            return sign == k.sign && deltaUlp == k.deltaUlp
                    && deltaExponent == k.deltaExponent;
        }

        @Override
        public int hashCode() {
            int h = sign;
            h = 31 * h + deltaUlp;
            return 31 * h + deltaExponent;
        }
    }

    private static final class BlockResult {
        final int index;
        final long values;
        final long zeroDeltas;
        final Map<Key, Long> counts;

        BlockResult(int index, long values, long zeroDeltas, Map<Key, Long> counts) {
            this.index = index;
            this.values = values;
            this.zeroDeltas = zeroDeltas;
            this.counts = counts;
        }
    }

    private static final class DatasetResult {
        final String dataset;
        final long values;
        final long zeroDeltas;
        final long invalidRows;
        final Map<Key, Long> counts;
        final List<BlockResult> blocks;

        DatasetResult(String dataset, long values, long zeroDeltas, long invalidRows,
                      Map<Key, Long> counts, List<BlockResult> blocks) {
            this.dataset = dataset;
            this.values = values;
            this.zeroDeltas = zeroDeltas;
            this.invalidRows = invalidRows;
            this.counts = counts;
            this.blocks = blocks;
        }
    }

    private static final class Region {
        final int ulpRadius;
        final int exponentRadius;
        final long covered;
        final long total;

        Region(int ulpRadius, int exponentRadius, long covered, long total) {
            this.ulpRadius = ulpRadius;
            this.exponentRadius = exponentRadius;
            this.covered = covered;
            this.total = total;
        }

        long cells() {
            return 2L * (2L * ulpRadius + 1L) * (2L * exponentRadius + 1L);
        }
    }

    private static final class CodebookChoice {
        final int codeBits;
        final List<Key> keys;

        CodebookChoice(int codeBits, List<Key> keys) {
            this.codeBits = codeBits;
            this.keys = keys;
        }
    }

    private static final class Analyzer {
        private final BigDecimal[] valueWin = new BigDecimal[1 << WIN_BITS];
        private final BigDecimal[] deltaWin = new BigDecimal[1 << WIN_BITS];
        private final Map<Key, Long> counts = new HashMap<>();
        private final List<BlockResult> blocks = new ArrayList<>();
        private Map<Key, Long> blockCounts = new HashMap<>();
        private int winIndex;
        private long values;
        private long zeroDeltas;
        private long blockValues;
        private long blockZeroDeltas;

        Analyzer() {
            Arrays.fill(valueWin, BigDecimal.valueOf(0.1));
            Arrays.fill(deltaWin, BigDecimal.valueOf(0.1));
        }

        void accept(double rawValue) {
            BigDecimal value = new BigDecimal(Double.toString(rawValue)).stripTrailingZeros();
            int bestI = 0;
            BigDecimal bestDelta = BigDecimal.ZERO;
            Key bestKey = null;
            int minBits = Integer.MAX_VALUE;

            for (int i = 0; i < valueWin.length; i++) {
                BigDecimal previousValue = valueWin[(winIndex + i) % valueWin.length];
                BigDecimal delta = subtractWithOriginalPrecision(value, previousValue);
                if (delta.signum() == 0) {
                    bestI = i;
                    bestDelta = delta;
                    bestKey = null;
                    break;
                }

                BigDecimal previousDelta = deltaWin[(winIndex + i) % deltaWin.length];
                int sign = delta.signum() > 0 ? 0 : 1;
                int deltaUlp = SALTEUtils.getUlpPlaces(delta)
                        - SALTEUtils.getUlpPlaces(previousDelta);
                int deltaExponent = SALTEUtils.getIeeeExponent(delta)
                        - SALTEUtils.getIeeeExponent(previousDelta);
                int mantissaBits = SALTEUtils.mantissaBitsToKeep(delta);
                int bits = WIN_BITS + 1 + 1 + exponentCost(deltaExponent)
                        + ulpCost(deltaUlp) + mantissaBits;

                if (bits < minBits) {
                    minBits = bits;
                    bestI = i;
                    bestDelta = delta;
                    bestKey = new Key(sign, deltaUlp, deltaExponent);
                }
            }

            values++;
            blockValues++;
            if (bestDelta.signum() == 0) {
                zeroDeltas++;
                blockZeroDeltas++;
            } else {
                increment(counts, bestKey);
                increment(blockCounts, bestKey);

                BigDecimal newValue = valueWin[(winIndex + bestI) % valueWin.length]
                        .add(bestDelta);
                valueWin[winIndex] = newValue;
                deltaWin[winIndex] = bestDelta;
                winIndex = (winIndex + 1) % valueWin.length;
            }

            if (blockValues == BLOCK_SIZE) finishBlock();
        }

        void finish() {
            if (blockValues > 0) finishBlock();
        }

        private void finishBlock() {
            blocks.add(new BlockResult(blocks.size(), blockValues, blockZeroDeltas,
                    blockCounts));
            blockCounts = new HashMap<>();
            blockValues = 0;
            blockZeroDeltas = 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Paths.get(args.length > 0 ? args[0] : "datasets/Overall");
        Path output = Paths.get(args.length > 1 ? args[1] : "results/codebook_distribution");
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input.toAbsolutePath());
        }
        Files.createDirectories(output);

        List<Path> files = csvFiles(input);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No CSV files found under: " + input.toAbsolutePath());
        }

        List<DatasetResult> datasets = new ArrayList<>();
        Map<Key, Long> global = new HashMap<>();
        long globalValues = 0;
        long globalZeros = 0;
        long globalInvalid = 0;

        for (Path file : files) {
            DatasetResult result = analyze(file);
            datasets.add(result);
            merge(global, result.counts);
            globalValues += result.values;
            globalZeros += result.zeroDeltas;
            globalInvalid += result.invalidRows;
            System.out.printf(Locale.ROOT,
                    "%-32s values=%8d symbols=%8d distinct=%5d zero=%6.2f%%%n",
                    result.dataset, result.values, total(result.counts), result.counts.size(),
                    percent(result.zeroDeltas, result.values));
        }

        writeKeyDistribution(output.resolve("key_distribution.csv"), global);
        writeDatasetSummary(output.resolve("dataset_summary.csv"), datasets);
        writeTopK(output.resolve("topk_coverage.csv"), datasets, global);
        writeRegions(output.resolve("concentration_regions.csv"), datasets, global);
        writeBlocks(output.resolve("block_summary.csv"), datasets);
        writeSummary(output.resolve("summary.csv"), input, files.size(), globalValues,
                globalZeros, globalInvalid, global, datasets);

        System.out.println("Results written to " + output.toAbsolutePath());
    }

    private static DatasetResult analyze(Path file) throws IOException {
        Analyzer analyzer = new Analyzer();
        long invalid = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String token = csvColumn(line, VALUE_COLUMN);
                if (token == null) {
                    invalid++;
                    continue;
                }
                try {
                    double value = Double.parseDouble(token.trim());
                    if (!Double.isFinite(value)) {
                        invalid++;
                    } else {
                        analyzer.accept(value);
                    }
                } catch (NumberFormatException ignored) {
                    invalid++;
                }
            }
        }
        analyzer.finish();
        String name = removeExtension(file.getFileName().toString());
        return new DatasetResult(name, analyzer.values, analyzer.zeroDeltas, invalid,
                analyzer.counts, analyzer.blocks);
    }

    private static List<Path> csvFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) return Collections.singletonList(input);
        try (Stream<Path> stream = Files.walk(input)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".csv"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    private static String csvColumn(String line, int wantedColumn) {
        int column = 0;
        boolean quoted = false;
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                if (column == wantedColumn) return value.toString();
                column++;
                value.setLength(0);
            } else if (column == wantedColumn) {
                value.append(c);
            }
        }
        return column == wantedColumn ? value.toString() : null;
    }

    private static void writeKeyDistribution(Path path, Map<Key, Long> counts)
            throws IOException {
        List<Map.Entry<Key, Long>> sorted = sorted(counts);
        long total = total(counts);
        long cumulative = 0;
        try (BufferedWriter out = writer(path)) {
            out.write("rank,sign,delta_u,delta_e,count,probability,cumulative_coverage\n");
            for (int i = 0; i < sorted.size(); i++) {
                Map.Entry<Key, Long> entry = sorted.get(i);
                cumulative += entry.getValue();
                Key k = entry.getKey();
                out.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.12f,%.12f%n",
                        i + 1, k.sign, k.deltaUlp, k.deltaExponent, entry.getValue(),
                        ratio(entry.getValue(), total), ratio(cumulative, total)));
            }
        }
    }

    private static void writeDatasetSummary(Path path, List<DatasetResult> datasets)
            throws IOException {
        try (BufferedWriter out = writer(path)) {
            out.write("dataset,values,nonzero_symbols,zero_deltas,zero_rate,invalid_rows,distinct_keys,entropy_bits,effective_keys,normalized_entropy,top1_coverage,top4_coverage,top8_coverage,top16_coverage,top32_coverage,top64_coverage\n");
            for (DatasetResult d : datasets) {
                long symbols = total(d.counts);
                double entropy = entropy(d.counts);
                out.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%.12f,%d,%d,%.8f,%.8f,%.8f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                        csv(d.dataset), d.values, symbols, d.zeroDeltas,
                        ratio(d.zeroDeltas, d.values), d.invalidRows, d.counts.size(), entropy,
                        Math.pow(2.0, entropy), normalizedEntropy(entropy, d.counts.size()),
                        topKCoverage(d.counts, 1), topKCoverage(d.counts, 4),
                        topKCoverage(d.counts, 8), topKCoverage(d.counts, 16),
                        topKCoverage(d.counts, 32), topKCoverage(d.counts, 64)));
            }
        }
    }

    private static void writeTopK(Path path, List<DatasetResult> datasets,
                                  Map<Key, Long> global) throws IOException {
        try (BufferedWriter out = writer(path)) {
            out.write("scope,dataset,k,covered,total,coverage,code_bits,estimated_net_savings_bits,estimated_savings_bits_per_symbol\n");
            writeTopKRows(out, "global", "ALL", global);
            for (DatasetResult d : datasets) writeTopKRows(out, "dataset", d.dataset, d.counts);
        }
    }

    private static void writeTopKRows(BufferedWriter out, String scope, String dataset,
                                      Map<Key, Long> counts) throws IOException {
        List<Map.Entry<Key, Long>> sorted = sorted(counts);
        long total = total(counts);
        for (int k : TOP_K) {
            int entries = Math.min(k, sorted.size());
            int codeBits = ceilLog2(k + 2);
            long covered = 0;
            long hitSavingsBeforeCode = 0;
            for (int i = 0; i < entries; i++) {
                Map.Entry<Key, Long> entry = sorted.get(i);
                covered += entry.getValue();
                int rawKeyBits = 1 + ulpCost(entry.getKey().deltaUlp)
                        + exponentCost(entry.getKey().deltaExponent);
                hitSavingsBeforeCode += entry.getValue() * rawKeyBits;
            }
            long overhead = 21L + 17L * entries;
            // Every symbol pays the code index. Only hits avoid their raw key payload.
            long net = hitSavingsBeforeCode - total * codeBits - overhead;
            out.write(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.12f,%d,%d,%.8f%n",
                    scope, csv(dataset), k, covered, total, ratio(covered, total), codeBits,
                    net, total == 0 ? 0.0 : (double) net / total));
        }
    }

    private static void writeRegions(Path path, List<DatasetResult> datasets,
                                     Map<Key, Long> global) throws IOException {
        try (BufferedWriter out = writer(path)) {
            out.write("scope,dataset,target_coverage,ulp_radius,exponent_radius,cells,covered,total,actual_coverage\n");
            writeRegionRows(out, "global", "ALL", global);
            for (DatasetResult d : datasets) writeRegionRows(out, "dataset", d.dataset, d.counts);
        }
    }

    private static void writeRegionRows(BufferedWriter out, String scope, String dataset,
                                        Map<Key, Long> counts) throws IOException {
        for (double target : REGION_TARGETS) {
            Region r = minimumCenteredRegion(counts, target);
            out.write(String.format(Locale.ROOT, "%s,%s,%.2f,%d,%d,%d,%d,%d,%.12f%n",
                    scope, csv(dataset), target, r.ulpRadius, r.exponentRadius, r.cells(),
                    r.covered, r.total, ratio(r.covered, r.total)));
        }
    }

    private static void writeBlocks(Path path, List<DatasetResult> datasets)
            throws IOException {
        try (BufferedWriter out = writer(path)) {
            out.write("dataset,block,values,nonzero_symbols,zero_rate,distinct_keys,top8_coverage,top16_coverage,top32_coverage,previous_top16_hit_rate,top16_jaccard,previous_top16_net_savings_bits_per_symbol,previous_top32_hit_rate,previous_top32_net_savings_bits_per_symbol,adaptive_code_bits,adaptive_codebook_size,adaptive_previous_block_hit_rate,adaptive_previous_block_net_savings_bits_per_symbol\n");
            for (DatasetResult d : datasets) {
                Map<Key, Long> previous = null;
                for (BlockResult block : d.blocks) {
                    long symbols = total(block.counts);
                    double previousHit = previous == null ? Double.NaN
                            : hitRate(block.counts, topKeys(previous, 16));
                    double jaccard = previous == null ? Double.NaN
                            : jaccard(topKeys(previous, 16), topKeys(block.counts, 16));
                    double previousNet16 = previous == null ? Double.NaN
                            : netSavingsPerSymbol(block.counts, topKeys(previous, 16),
                                    ceilLog2(16 + 2));
                    double previousHit32 = previous == null ? Double.NaN
                            : hitRate(block.counts, topKeys(previous, 32));
                    double previousNet32 = previous == null ? Double.NaN
                            : netSavingsPerSymbol(block.counts, topKeys(previous, 32),
                                    ceilLog2(32 + 2));
                    CodebookChoice adaptive = previous == null ? null : chooseCodebook(previous);
                    double adaptiveHit = adaptive == null ? Double.NaN
                            : hitRate(block.counts, adaptive.keys);
                    double adaptiveNet = adaptive == null ? Double.NaN
                            : netSavingsPerSymbol(block.counts, adaptive.keys, adaptive.codeBits);
                    out.write(String.format(Locale.ROOT,
                            "%s,%d,%d,%d,%.12f,%d,%.12f,%.12f,%.12f,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            csv(d.dataset), block.index, block.values, symbols,
                            ratio(block.zeroDeltas, block.values), block.counts.size(),
                            topKCoverage(block.counts, 8), topKCoverage(block.counts, 16),
                            topKCoverage(block.counts, 32), number(previousHit), number(jaccard),
                            number(previousNet16), number(previousHit32), number(previousNet32),
                            adaptive == null ? "" : Integer.toString(adaptive.codeBits),
                            adaptive == null ? "" : Integer.toString(adaptive.keys.size()),
                            number(adaptiveHit), number(adaptiveNet)));
                    previous = block.counts;
                }
            }
        }
    }

    private static void writeSummary(Path path, Path input, int fileCount, long values,
                                     long zeros, long invalidRows, Map<Key, Long> counts,
                                     List<DatasetResult> datasets)
            throws IOException {
        long symbols = total(counts);
        double entropy = entropy(counts);
        try (BufferedWriter out = writer(path)) {
            out.write("metric,value\n");
            row(out, "input", input.toAbsolutePath().toString());
            row(out, "selection_model", "encoder warm-up cost (codebook-independent)");
            row(out, "window_bits", Integer.toString(WIN_BITS));
            row(out, "window_size", Integer.toString(1 << WIN_BITS));
            row(out, "block_size", Integer.toString(BLOCK_SIZE));
            row(out, "files", Integer.toString(fileCount));
            row(out, "values", Long.toString(values));
            row(out, "nonzero_symbols", Long.toString(symbols));
            row(out, "zero_deltas", Long.toString(zeros));
            row(out, "zero_rate", decimal(ratio(zeros, values)));
            row(out, "invalid_rows", Long.toString(invalidRows));
            row(out, "distinct_keys", Integer.toString(counts.size()));
            row(out, "entropy_bits", decimal(entropy));
            row(out, "effective_keys", decimal(Math.pow(2.0, entropy)));
            row(out, "normalized_entropy", decimal(normalizedEntropy(entropy, counts.size())));
            for (int k : TOP_K) row(out, "top" + k + "_coverage", decimal(topKCoverage(counts, k)));
            for (int k : new int[]{16, 32}) {
                long laggedSymbols = 0;
                long laggedHits = 0;
                double laggedNet = 0.0;
                for (DatasetResult dataset : datasets) {
                    Map<Key, Long> previous = null;
                    for (BlockResult block : dataset.blocks) {
                        if (previous != null) {
                            List<Key> codebook = topKeys(previous, k);
                            long blockSymbols = total(block.counts);
                            laggedSymbols += blockSymbols;
                            for (Key key : codebook) {
                                laggedHits += block.counts.getOrDefault(key, 0L);
                            }
                            laggedNet += netSavings(block.counts, codebook, ceilLog2(k + 2));
                        }
                        previous = block.counts;
                    }
                }
                row(out, "previous_block_top" + k + "_hit_rate",
                        decimal(ratio(laggedHits, laggedSymbols)));
                row(out, "previous_block_top" + k + "_net_savings_bits_per_symbol",
                        decimal(laggedSymbols == 0 ? 0.0 : laggedNet / laggedSymbols));
            }
            long adaptiveSymbols = 0;
            long adaptiveHits = 0;
            long adaptiveNet = 0;
            long adaptiveBlocks = 0;
            for (DatasetResult dataset : datasets) {
                Map<Key, Long> previous = null;
                for (BlockResult block : dataset.blocks) {
                    if (previous != null) {
                        CodebookChoice choice = chooseCodebook(previous);
                        long blockSymbols = total(block.counts);
                        adaptiveSymbols += blockSymbols;
                        for (Key key : choice.keys) {
                            adaptiveHits += block.counts.getOrDefault(key, 0L);
                        }
                        adaptiveNet += netSavings(block.counts, choice.keys, choice.codeBits);
                        if (!choice.keys.isEmpty()) adaptiveBlocks++;
                    }
                    previous = block.counts;
                }
            }
            row(out, "adaptive_previous_block_evaluated_blocks", Long.toString(adaptiveBlocks));
            row(out, "adaptive_previous_block_hit_rate", decimal(ratio(adaptiveHits, adaptiveSymbols)));
            row(out, "adaptive_previous_block_net_savings_bits_per_symbol",
                    decimal(adaptiveSymbols == 0 ? 0.0 : (double) adaptiveNet / adaptiveSymbols));
            for (double target : REGION_TARGETS) {
                Region r = minimumCenteredRegion(counts, target);
                String prefix = "region_" + (int) Math.round(target * 100.0);
                row(out, prefix + "_ulp_radius", Integer.toString(r.ulpRadius));
                row(out, prefix + "_exponent_radius", Integer.toString(r.exponentRadius));
                row(out, prefix + "_cells", Long.toString(r.cells()));
                row(out, prefix + "_actual_coverage", decimal(ratio(r.covered, r.total)));
            }
        }
    }

    private static Region minimumCenteredRegion(Map<Key, Long> counts, double target) {
        long total = total(counts);
        if (total == 0) return new Region(0, 0, 0, 0);
        long required = (long) Math.ceil(target * total);
        List<Integer> ulpRadii = counts.keySet().stream().map(k -> Math.abs(k.deltaUlp))
                .distinct().sorted().collect(Collectors.toList());
        Region best = null;
        for (int u : ulpRadii) {
            Map<Integer, Long> byExponentRadius = new HashMap<>();
            long eligible = 0;
            for (Map.Entry<Key, Long> entry : counts.entrySet()) {
                if (Math.abs(entry.getKey().deltaUlp) <= u) {
                    int e = Math.abs(entry.getKey().deltaExponent);
                    byExponentRadius.put(e, byExponentRadius.getOrDefault(e, 0L)
                            + entry.getValue());
                    eligible += entry.getValue();
                }
            }
            if (eligible < required) continue;
            List<Integer> eRadii = new ArrayList<>(byExponentRadius.keySet());
            Collections.sort(eRadii);
            long covered = 0;
            int chosenE = 0;
            for (int e : eRadii) {
                covered += byExponentRadius.get(e);
                if (covered >= required) {
                    chosenE = e;
                    break;
                }
            }
            Region candidate = new Region(u, chosenE, covered, total);
            if (best == null || candidate.cells() < best.cells()
                    || (candidate.cells() == best.cells() && candidate.covered > best.covered)) {
                best = candidate;
            }
        }
        return best == null ? new Region(0, 0, 0, total) : best;
    }

    private static List<Map.Entry<Key, Long>> sorted(Map<Key, Long> counts) {
        List<Map.Entry<Key, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            if (byCount != 0) return byCount;
            Key x = a.getKey();
            Key y = b.getKey();
            int c = Integer.compare(x.sign, y.sign);
            if (c != 0) return c;
            c = Integer.compare(x.deltaUlp, y.deltaUlp);
            return c != 0 ? c : Integer.compare(x.deltaExponent, y.deltaExponent);
        });
        return entries;
    }

    private static List<Key> topKeys(Map<Key, Long> counts, int k) {
        List<Map.Entry<Key, Long>> entries = sorted(counts);
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < Math.min(k, entries.size()); i++) keys.add(entries.get(i).getKey());
        return keys;
    }

    private static double hitRate(Map<Key, Long> counts, List<Key> codebook) {
        long hits = 0;
        long total = total(counts);
        for (Key key : codebook) hits += counts.getOrDefault(key, 0L);
        return ratio(hits, total);
    }

    private static double netSavingsPerSymbol(Map<Key, Long> counts, List<Key> codebook,
                                              int codeBits) {
        long total = total(counts);
        return total == 0 ? 0.0 : (double) netSavings(counts, codebook, codeBits) / total;
    }

    private static long netSavings(Map<Key, Long> counts, List<Key> codebook, int codeBits) {
        long avoidedRawPayload = 0;
        for (Key key : codebook) {
            long hits = counts.getOrDefault(key, 0L);
            avoidedRawPayload += hits * (1 + ulpCost(key.deltaUlp)
                    + exponentCost(key.deltaExponent));
        }
        long indexCost = total(counts) * codeBits;
        long metadataCost = 21L + 17L * codebook.size();
        return avoidedRawPayload - indexCost - metadataCost;
    }

    /** Select on one block and evaluate only later; an empty choice means raw keys win. */
    private static CodebookChoice chooseCodebook(Map<Key, Long> trainingCounts) {
        CodebookChoice best = new CodebookChoice(0, Collections.emptyList());
        long bestSavings = 0;
        int distinct = trainingCounts.size();
        for (int bits = 1; bits < 16; bits++) {
            int capacity = (1 << bits) - 2;
            if (capacity <= 0) continue;
            List<Key> keys = topKeys(trainingCounts, capacity);
            long savings = netSavings(trainingCounts, keys, bits);
            if (savings > bestSavings) {
                bestSavings = savings;
                best = new CodebookChoice(bits, keys);
            }
            if (capacity >= distinct) break;
        }
        return best;
    }

    private static double jaccard(List<Key> a, List<Key> b) {
        int intersection = 0;
        for (Key key : a) if (b.contains(key)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static double topKCoverage(Map<Key, Long> counts, int k) {
        long covered = 0;
        List<Map.Entry<Key, Long>> entries = sorted(counts);
        for (int i = 0; i < Math.min(k, entries.size()); i++) covered += entries.get(i).getValue();
        return ratio(covered, total(counts));
    }

    private static double entropy(Map<Key, Long> counts) {
        long total = total(counts);
        if (total == 0) return 0.0;
        double h = 0.0;
        for (long count : counts.values()) {
            double p = (double) count / total;
            h -= p * (Math.log(p) / Math.log(2.0));
        }
        return h;
    }

    private static double normalizedEntropy(double entropy, int distinct) {
        return distinct <= 1 ? 0.0 : entropy / (Math.log(distinct) / Math.log(2.0));
    }

    private static int exponentCost(int deltaExponent) {
        int maxMagnitude = (1 << (EXPONENT_BITS - 1)) - 1;
        return EXPONENT_BITS + (Math.abs(deltaExponent) > maxMagnitude ? CODEBOOK_EXP_BITS : 0);
    }

    private static int ulpCost(int deltaUlp) {
        int maxMagnitude = (1 << (ULP_BITS - 1)) - 1;
        return ULP_BITS + (Math.abs(deltaUlp) > maxMagnitude ? 4 : 0);
    }

    private static BigDecimal subtractWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        int precision = Math.max(Math.max(a.scale(), 0), Math.max(b.scale(), 0));
        return a.subtract(b).setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static int ceilLog2(int value) {
        if (value <= 1) return 1;
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static void increment(Map<Key, Long> counts, Key key) {
        counts.put(key, counts.getOrDefault(key, 0L) + 1L);
    }

    private static void merge(Map<Key, Long> target, Map<Key, Long> source) {
        for (Map.Entry<Key, Long> entry : source.entrySet()) {
            target.put(entry.getKey(), target.getOrDefault(entry.getKey(), 0L)
                    + entry.getValue());
        }
    }

    private static long total(Map<Key, Long> counts) {
        long total = 0;
        for (long count : counts.values()) total += count;
        return total;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double percent(long numerator, long denominator) {
        return 100.0 * ratio(numerator, denominator);
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "" : decimal(value);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private static void row(BufferedWriter out, String metric, String value) throws IOException {
        out.write(csv(metric));
        out.write(',');
        out.write(csv(value));
        out.write('\n');
    }

    private static BufferedWriter writer(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path.toFile()),
                StandardCharsets.UTF_8));
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String csv(String text) {
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
