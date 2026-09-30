package algorithms.SALTE.encoder;

import algorithms.Encoder;
import algorithms.SALTE.SALTEUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

public class DoubleOurEEncoder extends Encoder {

    // ====== Meta (same meaning as your Our_With_Encode) ======
    private static final int WIN_BITS = 2;
    private static final int EXPONENT_BITS = 3;
    private static final int ULP_BITS = 3;

    // 0 => auto (same as your CODE_BITS=0)
    private static final int CODE_BITS = 0;

    private static final int BLOCK_SIZE = 10000;

    // ====== Codebook fixed widths (must match your decoder) ======
    private static final int CODEBOOK_ULP_BITS = 5;
    private static final int CODEBOOK_EXP_BITS = 11;

    private final int winCapacity = 1 << WIN_BITS;

    // Sliding window
    private final BigDecimal[] valueWin = new BigDecimal[winCapacity];
    private final BigDecimal[] deltaWin = new BigDecimal[winCapacity];
    private int winIndex = 0;

    // Global count of encoded values (NOT reset per block)
    private int blockCount = 0;

    // Frequency stats for building next codebook
    private final Map<Key, Integer> counts = new HashMap<>();

    // Active codebook for steady-state encoding: insertion order == code index order
    private LinkedHashMap<Key, Integer> prevCounts = new LinkedHashMap<>();
    private int minCountBits = 0;

    private final Probe probe = new Probe();

    private boolean headerWritten = false;

    // RAW is enabled by default. The decoder detects the format from its header.
    private final boolean rawEnabled;
    private long rawCount = 0;
    private long rejectedCandidates = 0;

    public long getRawCount() { return rawCount; }
    public boolean isRawEnabled() { return rawEnabled; }

    @Override
    public Map<String, Double> getMeta() {
        meta.put("raw_enabled", rawEnabled ? 1.0 : 0.0);
        meta.put("raw_count", (double) rawCount);
        meta.put("raw_percent", blockCount == 0 ? 0.0 : 100.0 * rawCount / blockCount);
        meta.put("rejected_candidates", (double) rejectedCandidates);
        return meta;
    }

    // ====== Compact key types (same idea as your Our_With_Encode) ======
    static final class Key {
        final int s, u, e; // sign, ulpDelta, expDelta
        Key(int s, int u, int e) { this.s = s; this.u = u; this.e = e; }

        @Override public int hashCode() {
            int h = s * 0x9E3779B9;
            h = (h ^ (u + 0x85EBCA6B)) * 0xC2B2AE35;
            return h ^ e;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof Key) {
                Key k = (Key) o;
                return s == k.s && u == k.u && e == k.e;
            }
            if (o instanceof Probe) {
                Probe p = (Probe) o;
                return s == p.s && u == p.u && e == p.e;
            }
            return false;
        }
    }

    static final class Probe {
        int s, u, e;
        void set(int s, int u, int e) { this.s = s; this.u = u; this.e = e; }

        @Override public int hashCode() {
            int h = s * 0x9E3779B9;
            h = (h ^ (u + 0x85EBCA6B)) * 0xC2B2AE35;
            return h ^ e;
        }
        @Override public boolean equals(Object o) {
            if (o instanceof Key) {
                Key k = (Key) o;
                return s == k.s && u == k.u && e == k.e;
            }
            if (o instanceof Probe) {
                Probe p = (Probe) o;
                return s == p.s && u == p.u && e == p.e;
            }
            return false;
        }
    }

    public DoubleOurEEncoder(String outputPath) {
        this(outputPath, Boolean.parseBoolean(System.getProperty("salt.raw.enabled", "true")));
    }

    public DoubleOurEEncoder(String outputPath, boolean rawEnabled) {
        super(outputPath);
        this.rawEnabled = rawEnabled;
        // The initial RAW-enabled codebook has no adaptive entries and three reserved codes.
        this.minCountBits = rawEnabled ? 2 : 0;
        // Init windows to 0.1 (same as your code)
        Arrays.fill(valueWin, BigDecimal.valueOf(0.1));
        Arrays.fill(deltaWin, BigDecimal.valueOf(0.1));
    }

    @Override
    public int encode(double v) {
        // Write meta header once (same as your .bin format)
        if (!headerWritten) {
            if (rawEnabled) {
                out.write(15, 4); // reserved extension marker, outside supported legacy window sizes
                out.write(2, 4); // version 2: RAW is a peer of ZERO and ESC
            }
            out.write(WIN_BITS, 4);
            out.write(EXPONENT_BITS, 4);
            out.write(ULP_BITS, 4);
            out.write(BLOCK_SIZE, 20);
            headerWritten = true;
        }

        // Insert codebook BEFORE encoding value when (blockCount % BLOCK_SIZE == BLOCK_SIZE-1)
        if (BLOCK_SIZE > 0 && (blockCount % BLOCK_SIZE == BLOCK_SIZE - 1)) {
            buildAndWriteCodebook();
            counts.clear();
        }

        if (rawEnabled && !Double.isFinite(v)) return writeRaw(v);
        BigDecimal value = new BigDecimal(Double.toString(v)).stripTrailingZeros();

        // Pick best window reference
        int bestI = 0;
        BigDecimal bestDelta = BigDecimal.ZERO;

        int bestSign = 0;
        int bestUlpDelta = 0;
        int bestExpDelta = 0;
        int bestManBits = 0;

        // For steady-state
        int bestCodeIndex = 0; // either codebook index, escapeCode, or zeroCode

        int minBits = Integer.MAX_VALUE;

        for (int i = 0; i < winCapacity; i++) {
            BigDecimal prevValue = valueWin[(winIndex + i) % winCapacity];
            BigDecimal delta = subtractWithOriginalPrecision(value, prevValue);
            BigDecimal prevDelta = deltaWin[(winIndex + i) % winCapacity];

            // delta == 0
            if (delta.signum() == 0) {
                int bits;
                if (!rawEnabled && blockCount < BLOCK_SIZE - 1) {
                    bits = WIN_BITS + 1; // [WIN][zeroFlag]
                } else {
                    bits = WIN_BITS + minCountBits; // [WIN][zeroCode]
                }
                if (bits < minBits) {
                    minBits = bits;
                    bestI = i;
                    bestDelta = delta;
                }
                break; // same as your original: stop searching if exact match
            }

            int sign = (delta.signum() > 0) ? 0 : 1;
            int ulpDelta = SALTEUtils.getUlpPlaces(delta) - SALTEUtils.getUlpPlaces(prevDelta);
            int expDelta = SALTEUtils.getIeeeExponent(delta) - SALTEUtils.getIeeeExponent(prevDelta);

            int manBits = SALTEUtils.mantissaBitsToKeep(delta);
            if (manBits > 52) manBits = 52;
            if (manBits < 0) manBits = 0;

            if (rawEnabled && !isReversibleCandidate(v, value, prevValue, delta,
                    sign, ulpDelta, expDelta, manBits)) {
                rejectedCandidates++;
                continue;
            }

            int bits;
            if (!rawEnabled && blockCount < BLOCK_SIZE - 1) {
                // warmup format: [WIN][zeroFlag=0][sign][expDelta(+exp11?)][ulpDelta(+ulp4?)][mantissa]
                bits = WIN_BITS + 1 + 1; // WIN + zeroFlag + sign
                bits += expDeltaCostBits(expDelta);
                bits += ulpDeltaCostBits(ulpDelta);
                bits += manBits;
            } else {
                // steady-state:
                // [WIN][code:minCountBits] + mantissa OR escape payload
                probe.set(sign, ulpDelta, expDelta);
                boolean hit = prevCounts.containsKey(probe);

                bits = WIN_BITS + minCountBits;
                if (!hit) {
                    // escape payload: sign + expDelta(+exp11?) + ulpDelta(+ulp4?)
                    bits += 1; // sign
                    bits += expDeltaCostBits(expDelta);
                    bits += ulpDeltaCostBits(ulpDelta);
                }
                bits += manBits;
            }

            if (bits < minBits) {
                minBits = bits;
                bestI = i;
                bestDelta = delta;
                bestSign = sign;
                bestUlpDelta = ulpDelta;
                bestExpDelta = expDelta;
                bestManBits = manBits;

                if (rawEnabled || blockCount >= BLOCK_SIZE - 1) {
                    probe.set(sign, ulpDelta, expDelta);
                    if (prevCounts.containsKey(probe)) {
                        bestCodeIndex = getRank(prevCounts, probe);
                    } else {
                        bestCodeIndex = (1 << minCountBits) - 2; // escape
                    }
                }
            }
        }

        if (rawEnabled && minBits == Integer.MAX_VALUE) return writeRaw(v);

        // ====== Write bits ======
        // write WIN index
        out.write(bestI, WIN_BITS);

        if (!rawEnabled && blockCount < BLOCK_SIZE - 1) {
            // warmup
            if (bestDelta.signum() == 0) {
                out.write(true); // zeroFlag = 1
            } else {
                out.write(false); // zeroFlag = 0
                out.write(bestSign, 1);

                writeExpDelta(bestDelta, bestExpDelta);
                writeUlpDelta(bestDelta, bestUlpDelta);

                writeMantissaTop(bestDelta.doubleValue(), bestManBits);
            }
        } else {
            // steady-state
            int zeroCode = (1 << minCountBits) - 1;
            int escapeCode = (1 << minCountBits) - 2;

            if (bestDelta.signum() == 0) {
                out.write(zeroCode, minCountBits);
            } else {
                out.write(bestCodeIndex, minCountBits);

                if (bestCodeIndex == escapeCode) {
                    out.write(bestSign, 1);
                    writeExpDelta(bestDelta, bestExpDelta);
                    writeUlpDelta(bestDelta, bestUlpDelta);
                } else {
                    // codebook hit: sign/expDelta/ulpDelta are implied by codebook entry
                }

                writeMantissaTop(bestDelta.doubleValue(), bestManBits);
            }
        }

        // ====== Update windows & stats (delta==0 does NOT advance window) ======
        if (bestDelta.signum() != 0) {
            BigDecimal newValue = valueWin[(winIndex + bestI) % winCapacity].add(bestDelta);

            valueWin[winIndex] = newValue;
            deltaWin[winIndex] = bestDelta;
            winIndex = (winIndex + 1) % winCapacity;

            Key k = new Key(bestSign, bestUlpDelta, bestExpDelta);
            // An ESC-representable transition need not fit a codebook entry.
            // Keep it on ESC instead of serializing an overflowing codebook key.
            if (!rawEnabled || (Math.abs(k.u) <= 15 && Math.abs(k.e) <= 1023)) {
                counts.put(k, counts.getOrDefault(k, 0) + 1);
            }
        }

        blockCount++;

        return out.track_bits();
    }

    /** Validate the actual decoder reconstruction, including future decimal window state. */
    private boolean isReversibleCandidate(double original, BigDecimal value,
            BigDecimal reference, BigDecimal delta, int sign, int ulpDelta,
            int expDelta, int manBits) {
        double d = delta.doubleValue();
        if (!Double.isFinite(d) || Math.abs(d) < Double.MIN_NORMAL) return false;
        int exponent = SALTEUtils.getIeeeExponent(delta);
        int places = SALTEUtils.getUlpPlaces(delta);
        probe.set(sign, ulpDelta, expDelta);
        boolean hit = (rawEnabled || blockCount >= BLOCK_SIZE - 1) && prevCounts.containsKey(probe);
        // Absolute precision is four bits only on the escaped precision path.
        if (!hit && Math.abs(ulpDelta) > ((1 << (ULP_BITS - 1)) - 1) && places > 15) {
            return false;
        }
        if (manBits != SALTEUtils.mantissaBitsToKeep(exponent, places)) return false;
        long top = SALTEUtils.getDoubleMantissaTopBits(d, manBits);
        double rebuilt = SALTEUtils.buildDouble(sign, exponent, top, manBits);
        BigDecimal decodedDelta = SALTEUtils.toScaledBigDecimal(rebuilt, places).stripTrailingZeros();
        BigDecimal decodedValue = SALTEUtils.addWithOriginalPrecision(reference, decodedDelta);
        // Matching only today's double can hide different decimal states and corrupt later values.
        return decodedDelta.compareTo(delta) == 0 && decodedValue.compareTo(value) == 0
                && (original == decodedValue.doubleValue()); // signed zeros deliberately equivalent
    }

    /**
     * RAW is a reserved codeword at 2^b-3; ESC=2^b-2 and ZERO=2^b-1.
     * This format is used from the first value (initial code width b=2).
     * Payload: WIN(0), RAW codeword, raw64. No sign/exponent/precision fields follow.
     * RAW never advances windows or statistics.
     */
    private int writeRaw(double value) {
        out.write(0, WIN_BITS);
        out.write((1 << minCountBits) - 3, minCountBits);
        out.write(Double.doubleToRawLongBits(value), 64);
        rawCount++;
        blockCount++;
        return out.track_bits();
    }

    // Codebook build + write

    private void buildAndWriteCodebook() {
        int distinct = counts.size();
        int reservedCodes = rawEnabled ? 3 : 2;
        int minimumCodeBits = rawEnabled ? 2 : 1;

        if (CODE_BITS == 0) {
            int countBits = minimumCodeBits;
            long minBlockCost = Long.MAX_VALUE;
            int bestBits = minimumCodeBits;

            // enumerate until (1<<countBits) >= distinct
            while ((1 << countBits) < distinct) {
                int cap = (1 << countBits) - reservedCodes;
                int saved = 0;
                int keySum = 0;

                if (cap > 0) {
                    Map<Key, Integer> top = getTopN(counts, cap);
                    for (Map.Entry<Key, Integer> en : top.entrySet()) {
                        Key k = en.getKey();
                        int freq = en.getValue();

                        int keyBitsCost = 1; // sign always in payload
                        keyBitsCost += ulpDeltaCostBits(k.u);
                        keyBitsCost += expDeltaCostBits(k.e);

                        saved += (keyBitsCost - countBits) * freq;
                        keySum += freq;
                    }
                }

                long cost = (long) countBits * (BLOCK_SIZE - keySum) - saved;
                if (cost < minBlockCost) {
                    minBlockCost = cost;
                    bestBits = countBits;
                }
                countBits++;
            }

            minCountBits = bestBits;
            int finalCap = (1 << minCountBits) - reservedCodes;

            LinkedHashMap<Key, Integer> codebook;
            if (finalCap > 0) {
                codebook = new LinkedHashMap<>(getTopN(counts, finalCap));
            } else {
                codebook = new LinkedHashMap<>();
            }
            prevCounts = codebook;
        } else {
            minCountBits = CODE_BITS;
            if (minCountBits < minimumCodeBits) {
                throw new IllegalArgumentException("Insufficient code bits for reserved codewords");
            }
            int cap = (1 << minCountBits) - reservedCodes;
            prevCounts = new LinkedHashMap<>(getTopN(counts, Math.max(0, cap)));
        }

        writeCodebookLine(minCountBits, prevCounts);
    }

    /**
     * Codebook segment format (bitstream, MSB-first):
     *   [minCountBits:5][size:16] then each entry:
     *   [sign:1][ulpDelta:5 sign-mag][expDelta:11 sign-mag]
     */
    private void writeCodebookLine(int minCountBits, LinkedHashMap<Key, Integer> codebook) {
        int size = (codebook == null) ? 0 : codebook.size();

        out.write(minCountBits, 5);
        out.write(size, 16);

        if (size == 0) return;

        for (Key k : codebook.keySet()) {
            if (k.s != 0 && k.s != 1) {
                throw new IllegalArgumentException("Codebook Key.s must be 0/1: " + k.s);
            }
            if (Math.abs(k.u) > 15) {
                throw new IllegalArgumentException("Codebook Key.u out of range for 5-bit sign-mag [-15,15]: " + k.u);
            }
            if (Math.abs(k.e) > 1023) {
                throw new IllegalArgumentException("Codebook Key.e out of range for 11-bit sign-mag [-1023,1023]: " + k.e);
            }

            out.write(k.s, 1);
            long uBits = SALTEUtils.encodeSignMagnitudeBits(k.u, CODEBOOK_ULP_BITS);
            out.write(uBits, CODEBOOK_ULP_BITS);

            long eBits = SALTEUtils.encodeSignMagnitudeBits(k.e, CODEBOOK_EXP_BITS);
            out.write(eBits, CODEBOOK_EXP_BITS);
        }
    }

    // Field writers

    private void writeExpDelta(BigDecimal delta, int expDelta) {
        // write expDelta in EXPONENT_BITS sign-mag with "-0" sentinel
        long bits = SALTEUtils.encodeSignMagnitudeBits(expDelta, EXPONENT_BITS);
        out.write(bits, EXPONENT_BITS);

        // overflow sentinel => write absolute exponent in 11-bit sign-mag
        int maxMag = (1 << (EXPONENT_BITS - 1)) - 1;
        if (Math.abs(expDelta) > maxMag) {
            int absExp = SALTEUtils.getIeeeExponent(delta);
            long exp11 = SALTEUtils.encodeSignMagnitudeBits(absExp, CODEBOOK_EXP_BITS);
            out.write(exp11, CODEBOOK_EXP_BITS);
        }
    }

    private void writeUlpDelta(BigDecimal delta, int ulpDelta) {
        long bits = SALTEUtils.encodeSignMagnitudeBits(ulpDelta, ULP_BITS);
        out.write(bits, ULP_BITS);

        int maxMag = (1 << (ULP_BITS - 1)) - 1;
        if (Math.abs(ulpDelta) > maxMag) {
            int ulpPlaces = SALTEUtils.getUlpPlaces(delta);
            int ulp4 = Math.min(15, ulpPlaces);
            out.write(ulp4, 4);
        }
    }

    private void writeMantissaTop(double d, int manBits) {
        if (manBits <= 0) return;
        if (manBits > 52) manBits = 52;
        long top = SALTEUtils.getDoubleMantissaTopBits(d, manBits);
        out.write(top, manBits);
    }

    // Costs & helpers

    private static int expDeltaCostBits(int expDelta) {
        int bits = EXPONENT_BITS;
        int maxMag = (1 << (EXPONENT_BITS - 1)) - 1;
        if (Math.abs(expDelta) > maxMag) bits += CODEBOOK_EXP_BITS; // +11
        return bits;
    }

    private static int ulpDeltaCostBits(int ulpDelta) {
        int bits = ULP_BITS;
        int maxMag = (1 << (ULP_BITS - 1)) - 1;
        if (Math.abs(ulpDelta) > maxMag) bits += 4;
        return bits;
    }

    private static BigDecimal subtractWithOriginalPrecision(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) throw new IllegalArgumentException("Arguments cannot be null");
        int pa = Math.max(a.scale(), 0);
        int pb = Math.max(b.scale(), 0);
        int p = Math.max(pa, pb);
        return a.subtract(b).setScale(p, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static Map<Key, Integer> getTopN(Map<Key, Integer> map, int n) {
        if (n <= 0 || map == null || map.isEmpty()) return Collections.emptyMap();

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

    private static int getRank(LinkedHashMap<Key, Integer> map, Object keyLike) {
        int idx = 0;
        for (Key k : map.keySet()) {
            if (k.equals(keyLike)) return idx;
            idx++;
        }
        // should never happen if caller checked containsKey
        return (idx > 0) ? (idx - 1) : 0;
    }
}
