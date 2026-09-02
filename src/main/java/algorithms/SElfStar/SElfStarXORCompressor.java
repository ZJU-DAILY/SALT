package algorithms.SElfStar;

import utils.StreamWriter;

import java.util.Arrays;

public class SElfStarXORCompressor {
    private final int[] leadingRepresentation = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7
    };
    private final int[] leadingRound = {
            0, 0, 0, 0, 0, 0, 0, 0,
            8, 8, 8, 8, 12, 12, 12, 12,
            16, 16, 18, 18, 20, 20, 22, 22,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24
    };
    private final int[] trailingRepresentation = {
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 1, 1,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 6, 6, 6, 6, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
    };
    private final int[] trailingRound = {
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 22, 22,
            22, 22, 22, 22, 28, 28, 28, 28,
            32, 32, 32, 32, 36, 36, 36, 36,
            40, 40, 42, 42, 42, 42, 46, 46,
            46, 46, 46, 46, 46, 46, 46, 46,
            46, 46, 46, 46, 46, 46, 46, 46,
    };
    private final int[] leadDistribution = new int[64];
    private final int[] trailDistribution = new int[64];
    private int storedLeadingZeros = Integer.MAX_VALUE;
    private int storedTrailingZeros = Integer.MAX_VALUE;
    private long storedVal = 0;
    private boolean first = true;
    private int[] leadPositions = {0, 8, 12, 16, 18, 20, 22, 24};
    private int[] trailPositions = {0, 22, 28, 32, 36, 40, 42, 46};
    private boolean updatePositions = false;
    private boolean writePositions = false;
    private final StreamWriter out;

    private int leadingBitsPerValue = 3;

    private int trailingBitsPerValue = 3;


    public SElfStarXORCompressor(StreamWriter out) {
        this.out = out;
    }

    public void addValue(long value) {
        if (first) {
            if (writePositions) {
                out.write(true);
                PostOfficeSolver.writePositions(leadPositions, out);
                PostOfficeSolver.writePositions(trailPositions, out);
                writeFirst(value);
            } else {
                out.write(false);
                writeFirst(value);
            }
        } else {
            compressValue(value);
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


    public void close() {
        if (updatePositions) {
            // we update distribution using the inner info
            leadPositions = PostOfficeSolver.initRoundAndRepresentation(leadDistribution, leadingRepresentation, leadingRound);
            leadingBitsPerValue = PostOfficeSolver.positionLength2Bits[leadPositions.length];

            trailPositions = PostOfficeSolver.initRoundAndRepresentation(trailDistribution, trailingRepresentation, trailingRound);
            trailingBitsPerValue = PostOfficeSolver.positionLength2Bits[trailPositions.length];
        }
        writePositions = updatePositions;
    }


    private void compressValue(long value) {
        long xor = storedVal ^ value;

        if (xor == 0) {
            // case 01
            out.write(1, 2);
        } else {
            int leadingZeros = leadingRound[Long.numberOfLeadingZeros(xor)];
            int trailingZeros = trailingRound[Long.numberOfTrailingZeros(xor)];
            leadDistribution[Long.numberOfLeadingZeros(xor)]++;
            trailDistribution[Long.numberOfTrailingZeros(xor)]++;

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
            }
            storedVal = value;
        }
    }

    public void refresh() {
        first = true;
        updatePositions = false;
        Arrays.fill(leadDistribution, 0);
        Arrays.fill(trailDistribution, 0);
    }
}
