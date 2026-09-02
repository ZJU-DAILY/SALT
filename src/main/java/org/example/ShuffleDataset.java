package org.example;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Randomly permutes one CSV value column without changing any value text.
 *
 * <p>By default this recursively processes datasets/Overall, shuffles column 1
 * (the second column used by CompBuilder), and mirrors file names and subfolders
 * under datasets/Overall_shuffled. Other columns remain at their original rows.</p>
 *
 * <p>Example:</p>
 * <pre>
 * java org.example.ShuffleDataset -in datasets/Overall
 *        -out datasets/Overall_shuffled -seed 20260819 -column 1
 * </pre>
 */
public final class ShuffleDataset {
    private static final String DEFAULT_INPUT = "datasets/Overall";
    private static final String DEFAULT_OUTPUT = "datasets/Overall_shuffled";
    private static final long DEFAULT_SEED = 42;
    private static final int DEFAULT_COLUMN = 1;

    private ShuffleDataset() {}

    private static final class Config {
        Path input = Paths.get(DEFAULT_INPUT);
        Path output = Paths.get(DEFAULT_OUTPUT);
        long seed = DEFAULT_SEED;
        int column = DEFAULT_COLUMN;
        boolean hasHeader = false;
    }

    private static final class Span {
        final int start;
        final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);
        validate(config);

        List<Path> csvFiles = findCsvFiles(config.input);
        if (csvFiles.isEmpty()) {
            throw new IllegalArgumentException("No CSV files found under: " + config.input);
        }

        // The destination must be new, so existing data can never be overwritten.
        Files.createDirectories(config.output);
        int completed = 0;
        try {
            for (Path source : csvFiles) {
                Path relative = Files.isDirectory(config.input)
                        ? config.input.relativize(source)
                        : source.getFileName();
                Path destination = config.output.resolve(relative);
                long fileSeed = mixSeed(config.seed, relative.toString());
                shuffleFile(source, destination, config.column, config.hasHeader, fileSeed);
                completed++;
                System.out.println("Shuffled: " + relative);
            }
        } catch (Exception error) {
            throw new IOException("Stopped after " + completed + " of " + csvFiles.size()
                    + " files. The new output folder was not deleted: " + config.output, error);
        }

        System.out.println("Finished " + completed + " CSV files. Output: "
                + config.output.toAbsolutePath());
    }

    private static Config parseArgs(String[] args) {
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-in".equals(arg)) {
                config.input = Paths.get(requireValue(args, ++i, arg));
            } else if ("-out".equals(arg)) {
                config.output = Paths.get(requireValue(args, ++i, arg));
            } else if ("-seed".equals(arg)) {
                config.seed = Long.parseLong(requireValue(args, ++i, arg));
            } else if ("-column".equals(arg)) {
                config.column = Integer.parseInt(requireValue(args, ++i, arg));
            } else if ("-header".equals(arg)) {
                config.hasHeader = true;
            } else if ("-help".equals(arg) || "--help".equals(arg)) {
                printUsage();
                System.exit(0);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return config;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value after " + option);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Usage: java org.example.ShuffleDataset "
                + "[-in path] [-out path] [-seed number] [-column zeroBasedIndex] [-header]");
        System.out.println("Defaults: -in " + DEFAULT_INPUT + " -out " + DEFAULT_OUTPUT
                + " -seed " + DEFAULT_SEED + " -column " + DEFAULT_COLUMN);
    }

    private static void validate(Config config) throws IOException {
        config.input = config.input.toAbsolutePath().normalize();
        config.output = config.output.toAbsolutePath().normalize();
        if (!Files.exists(config.input)) {
            throw new IllegalArgumentException("Input does not exist: " + config.input);
        }
        if (config.column < 0) {
            throw new IllegalArgumentException("Column index must be >= 0");
        }
        if (config.output.equals(config.input)) {
            throw new IllegalArgumentException("Output must differ from input");
        }
        if (Files.isDirectory(config.input) && config.output.startsWith(config.input)) {
            throw new IllegalArgumentException("Output cannot be inside the input folder");
        }
        if (Files.exists(config.output)) {
            throw new IllegalArgumentException("Output already exists; choose a new folder: "
                    + config.output);
        }
    }

    private static List<Path> findCsvFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) {
            if (!isCsv(input)) {
                throw new IllegalArgumentException("Input file is not CSV: " + input);
            }
            return Collections.singletonList(input);
        }
        try (Stream<Path> paths = Files.walk(input)) {
            return paths.filter(Files::isRegularFile)
                    .filter(ShuffleDataset::isCsv)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    private static boolean isCsv(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".csv");
    }

    private static void shuffleFile(Path source, Path destination, int column,
                                    boolean hasHeader, long seed) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        int firstDataRow = hasHeader && !lines.isEmpty() ? 1 : 0;
        List<String> values = new ArrayList<>(Math.max(0, lines.size() - firstDataRow));
        List<Span> spans = new ArrayList<>(values.size());

        for (int row = firstDataRow; row < lines.size(); row++) {
            Span span = findColumn(lines.get(row), column);
            if (span == null) {
                throw new IllegalArgumentException("Missing column " + column + " in "
                        + source + " at line " + (row + 1));
            }
            spans.add(span);
            values.add(lines.get(row).substring(span.start, span.end));
        }

        Map<String, Integer> originalCounts = frequencies(values);
        // Sattolo's algorithm forms one cycle, so every row occurrence moves when n > 1.
        derange(values, new Random(seed));
        if (!originalCounts.equals(frequencies(values))) {
            throw new IllegalStateException("Value multiset changed unexpectedly: " + source);
        }

        List<String> shuffledLines = new ArrayList<>(lines);
        for (int i = 0; i < values.size(); i++) {
            int row = firstDataRow + i;
            String originalLine = lines.get(row);
            Span span = spans.get(i);
            shuffledLines.set(row, originalLine.substring(0, span.start) + values.get(i)
                    + originalLine.substring(span.end));
        }

        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (String line : shuffledLines) {
                writer.write(line);
                writer.newLine();
            }
        }
        moveNewFile(temporary, destination);
    }

    private static Span findColumn(String line, int wantedColumn) {
        int column = 0;
        int fieldStart = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                if (column == wantedColumn) return new Span(fieldStart, i);
                column++;
                fieldStart = i + 1;
            }
        }
        return column == wantedColumn ? new Span(fieldStart, line.length()) : null;
    }

    private static Map<String, Integer> frequencies(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String value : values) {
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        return counts;
    }

    private static void derange(List<String> values, Random random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i); // j is strictly smaller than i: no fixed positions
            Collections.swap(values, i, j);
        }
    }

    private static long mixSeed(long seed, String relativePath) {
        long mixed = seed ^ relativePath.replace('\\', '/').hashCode();
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53l;
        return mixed ^ (mixed >>> 33);
    }

    private static void moveNewFile(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination);
        }
    }
}
