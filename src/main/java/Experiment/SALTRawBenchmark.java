package Experiment;

import algorithms.SALTE.decoder.DoubleOurEDecoder;
import algorithms.SALTE.encoder.DoubleOurEEncoder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Whole-file RAW on/off size comparison, with independent decoding of every input value. */
public final class SALTRawBenchmark {
    private static final class Result {
        long bytes, bits, raw, rejected, mismatches, zeroSigns, decoded;
        String error = "", hash;
    }

    private static Result run(double[] values, Path path, boolean rawEnabled) throws Exception {
        Result r = new Result();
        DoubleOurEEncoder encoder = new DoubleOurEEncoder(path.toString(), rawEnabled);
        for (double value : values) r.bits += encoder.encode(value);
        encoder.flush();
        r.raw = encoder.getRawCount();
        r.rejected = encoder.getMeta().get("rejected_candidates").longValue();
        r.bytes = Files.size(path);
        r.hash = hash(path);
        DoubleOurEDecoder decoder = new DoubleOurEDecoder(path.toString());
        try {
            for (double expected : values) {
                double actual = decoder.decodeDouble();
                r.decoded++;
                if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                    if (expected == 0.0 && actual == 0.0) r.zeroSigns++;
                    else r.mismatches++;
                }
            }
        } catch (RuntimeException e) { r.error = e.toString(); }
        return r;
    }

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args.length > 0 ? args[0] : "datasets/Overall");
        Path output = Paths.get(args.length > 1 ? args[1] : "work/salt-raw-benchmark-" + System.currentTimeMillis());
        Files.createDirectory(output); // Never overwrite an earlier experiment.
        Files.createDirectory(output.resolve("streams"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(input)) {
            files = stream.filter(p -> p.toString().endsWith(".csv")).sorted().collect(Collectors.toList());
        }
        Files.write(output.resolve("protocol.txt"), Arrays.asList(
                "Java=" + System.getProperty("java.version"),
                "Each file is one stream; internal block=10000, reference window=4, original adaptive codebook policy.",
                "CSV second column; first record skipped, matching Main; no sampling; all remaining rows consumed.",
                "ACB includes serialized header and padding. Also report original encode() bit counter ACB.",
                "Compression percent=compressed bytes/(8*records)*100; lower is better.",
                "RAW on: full reconstruction guard, original raw64, no window/statistics push; signed zeros equivalent.",
                "RAW on format v2: RAW/ESC/ZERO are peer codewords; initial width=2; adaptive capacity=2^b-3.",
                "RAW off: legacy encoder behavior and byte format; correctness checked independently.",
                "No speed claim: this is a size/correctness audit, not a warmed throughput benchmark."
        ), StandardCharsets.UTF_8);
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(output.resolve("summary.csv"), StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("dataset", "records", "source_sha256", "raw_count", "raw_percent", "rejected_candidates",
                    "off_bytes", "on_bytes", "off_acb", "on_acb", "delta_acb", "off_compressed_percent", "on_compressed_percent",
                    "off_counter_acb", "on_counter_acb", "off_mismatches", "on_mismatches", "on_zero_sign_differences",
                    "off_decoded", "on_decoded", "off_error", "on_error", "off_sha256", "on_sha256");
            for (Path file : files) {
                double[] values = CheckedBenchmark.read(file, 1, "yes", 0)[0];
                String name = file.getFileName().toString();
                Result off = run(values, output.resolve("streams/" + name + ".off.bin"), false);
                Result on = run(values, output.resolve("streams/" + name + ".on.bin"), true);
                int n = values.length;
                csv.printRecord(name, n, hash(file), on.raw, 100.0 * on.raw / n, on.rejected,
                        off.bytes, on.bytes, off.bytes * 8.0 / n, on.bytes * 8.0 / n, (on.bytes - off.bytes) * 8.0 / n,
                        off.bytes * 100.0 / (8.0 * n), on.bytes * 100.0 / (8.0 * n),
                        off.bits / (double) n, on.bits / (double) n, off.mismatches, on.mismatches, on.zeroSigns,
                        off.decoded, on.decoded, off.error, on.error, off.hash, on.hash);
                csv.flush();
                System.out.printf(Locale.ROOT, "%s n=%d RAW=%d (%.4f%%) ACB=%.6f -> %.6f mismatches=%d/%d%n",
                        name, n, on.raw, 100.0 * on.raw / n, off.bytes * 8.0 / n, on.bytes * 8.0 / n, off.mismatches, on.mismatches);
                if (on.mismatches != 0 || on.decoded != n || !on.error.isEmpty()) {
                    throw new IllegalStateException("RAW-on roundtrip failed: " + name + " " + on.error);
                }
            }
        }
    }

    private static String hash(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
