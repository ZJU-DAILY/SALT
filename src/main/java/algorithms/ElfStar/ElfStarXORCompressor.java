package algorithms.ElfStar;

import utils.StreamWriter;

import java.util.Arrays;

public class ElfStarXORCompressor{
    private final int[] leadingRepresentation = new int[64];
    private final int[] leadingRound = new int[64];
    private final int[] trailingRepresentation = new int[64];
    private final int[] trailingRound = new int[64];
    private int storedLeadingZeros = Integer.MAX_VALUE;
    private int storedTrailingZeros = Integer.MAX_VALUE;
    private long storedVal = 0;
    private boolean first = true;
    private int[] leadDistribution;
    private int[] trailDistribution;

    private final int window = 1000;
    private final StreamWriter out;

    private int leadingBitsPerValue;

    private int trailingBitsPerValue;


    public ElfStarXORCompressor(StreamWriter out) {
        this.out = out;
    }


    private int initLeadingRoundAndRepresentation(int[] distribution) {
        int[] positions = PostOfficeSolver.initRoundAndRepresentation(distribution, leadingRepresentation, leadingRound);
        leadingBitsPerValue = PostOfficeSolver.positionLength2Bits[positions.length];
        return PostOfficeSolver.writePositions(positions, out);
    }

    private int initTrailingRoundAndRepresentation(int[] distribution) {
        int[] positions = PostOfficeSolver.initRoundAndRepresentation(distribution, trailingRepresentation, trailingRound);
        trailingBitsPerValue = PostOfficeSolver.positionLength2Bits[positions.length];
        return PostOfficeSolver.writePositions(positions, out);
    }

    /**
     * Adds a new long value to the series. Note, values must be inserted in order.
     *
     * @param value next floating point value in the series
     */
    public int addValue(long value) {
        if (first) {
            return initLeadingRoundAndRepresentation(leadDistribution)
                    + initTrailingRoundAndRepresentation(trailDistribution)
                    + writeFirst(value);
        } else {
            return compressValue(value);
        }
    }

    private int writeFirst(long value) {
        first = false;
        storedVal = value;
        int trailingZeros = Long.numberOfTrailingZeros(value);
        out.write(trailingZeros, 7);
        if (trailingZeros < 64) {
            out.write(storedVal >>> (trailingZeros + 1), 63 - trailingZeros);
            return 70 - trailingZeros;
        } else {
            return 7;
        }
    }


    private int compressValue(long value) {
        int thisSize = 0;
        long xor = storedVal ^ value;

        if (xor == 0) {
            // case 01
            out.write(1, 2);
            thisSize += 2;
        } else {
            int leadingZeros = leadingRound[Long.numberOfLeadingZeros(xor)];
            int trailingZeros = trailingRound[Long.numberOfTrailingZeros(xor)];

            if (leadingZeros >= storedLeadingZeros && trailingZeros >= storedTrailingZeros &&
                    (leadingZeros - storedLeadingZeros) + (trailingZeros - storedTrailingZeros) < 1 + leadingBitsPerValue + trailingBitsPerValue) {
                // case 1
                int centerBits = 64 - storedLeadingZeros - storedTrailingZeros;
                int len = 1 + centerBits;
                if (len > 64) {
                    out.write(1, 1);
                    out.write(xor >>> storedTrailingZeros, centerBits);
                } else {
                    out.write((1L << centerBits) | (xor >>> storedTrailingZeros), 1 + centerBits);
                }
                thisSize += len;
            } else {
                storedLeadingZeros = leadingZeros;
                storedTrailingZeros = trailingZeros;
                int centerBits = 64 - storedLeadingZeros - storedTrailingZeros;

                // case 00
                int len = 2 + leadingBitsPerValue + trailingBitsPerValue + centerBits;
                if (len > 64) {
                    out.write((leadingRepresentation[storedLeadingZeros] << trailingBitsPerValue)
                            | trailingRepresentation[storedTrailingZeros], 2 + leadingBitsPerValue + trailingBitsPerValue);
                    out.write(xor >>> storedTrailingZeros, centerBits);
                } else {
                    out.write(
                            ((((long) leadingRepresentation[storedLeadingZeros] << trailingBitsPerValue) |
                                    trailingRepresentation[storedTrailingZeros]) << centerBits) | (xor >>> storedTrailingZeros),
                            len
                    );
                }
                thisSize += len;
            }
            storedVal = value;
        }
        return thisSize;
    }

    public void setDistribution(int[] leadDistribution, int[] trailDistribution) {
        this.leadDistribution = leadDistribution;
        this.trailDistribution = trailDistribution;
    }

    public void refresh() {
        storedLeadingZeros = Integer.MAX_VALUE;
        storedTrailingZeros = Integer.MAX_VALUE;
        storedVal = 0;
        first = true;
        Arrays.fill(leadingRepresentation, 0);
        Arrays.fill(leadingRound, 0);
        Arrays.fill(trailingRepresentation, 0);
        Arrays.fill(trailingRound, 0);
    }
}
