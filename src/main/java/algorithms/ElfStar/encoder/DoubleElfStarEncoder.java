package algorithms.ElfStar.encoder;

import algorithms.ElfStar.Elf64Utils;
import algorithms.ElfStar.ElfStarXORCompressor;
import algorithms.ElfStar.Huffman.Code;
import algorithms.ElfStar.Huffman.HuffmanEncode;
import algorithms.Encoder;

import java.util.Arrays;

// From https://github.com/Spatio-Temporal-Lab/SElfStar
public class DoubleElfStarEncoder extends Encoder {
    private final ElfStarXORCompressor xorCompressor;
    private final int window = 1000;
    private final int[] betaStarList = new int[window];
    private final long[] vPrimeList = new long[window];
    private final int[] leadDistribution = new int[64];
    private final int[] trailDistribution = new int[64];
    private int lastBetaStar = Integer.MAX_VALUE;
    private int numberOfValues = 0;
    private final int[] frequency = new int[17];    // 0 is for 10-i, 16 is for not erasing
    private Code[] huffmanCode;


    public DoubleElfStarEncoder(String outputpath) {
        super(outputpath);
        xorCompressor = new ElfStarXORCompressor(this.out);
    }

    public void addValue(double v) {
        long vLong = Double.doubleToRawLongBits(v);

        if (v == 0.0 || Double.isInfinite(v)) {
            vPrimeList[numberOfValues] = vLong;
            betaStarList[numberOfValues] = Integer.MAX_VALUE;
            frequency[16]++;
        } else if (Double.isNaN(v)) {
            vPrimeList[numberOfValues] = 0x7ff8000000000000L;
            betaStarList[numberOfValues] = Integer.MAX_VALUE;
            frequency[16]++;
        } else {
            // C1: v is a normal or subnormal
            int[] alphaAndBetaStar = Elf64Utils.getAlphaAndBetaStar(v, lastBetaStar);
            int e = ((int) (vLong >> 52)) & 0x7ff;
            int gAlpha = Elf64Utils.getFAlpha(alphaAndBetaStar[0]) + e - 1023;
            int eraseBits = 52 - gAlpha;
            long mask = 0xffffffffffffffffL << eraseBits;
            long delta = (~mask) & vLong;
            if (delta != 0 && eraseBits > 4) {  // C2
                lastBetaStar = alphaAndBetaStar[1];
                betaStarList[numberOfValues] = lastBetaStar;
                vPrimeList[numberOfValues] = mask & vLong;
                frequency[lastBetaStar]++;
            } else {
                betaStarList[numberOfValues] = Integer.MAX_VALUE;
                vPrimeList[numberOfValues] = vLong;
                frequency[16]++;
            }
        }
        numberOfValues++;
    }

    private void calculateDistribution() {
        long lastValue = vPrimeList[0];
        for (int i = 1; i < numberOfValues; i++) {
            long xor = lastValue ^ vPrimeList[i];
            if (xor != 0) {
                trailDistribution[Long.numberOfTrailingZeros(xor)]++;
                leadDistribution[Long.numberOfLeadingZeros(xor)]++;
                lastValue = vPrimeList[i];
            }
        }
    }

    private void compress() {
        huffmanCode = HuffmanEncode.getHuffmanCodes(frequency);
        HuffmanEncode.writeHuffmanCodes(this.out, huffmanCode);
        xorCompressor.setDistribution(leadDistribution, trailDistribution);
        for (int i = 0; i < numberOfValues; i++) {
            if (betaStarList[i] == Integer.MAX_VALUE) {
                out.write(huffmanCode[16].code, huffmanCode[16].length); // not erase
            } else {
                out.write(huffmanCode[betaStarList[i]].code, huffmanCode[betaStarList[i]].length);  // case 11, 2 + 4 = 6
            }
            xorCompressor.addValue(vPrimeList[i]);
        }
    }

    private void init(){
        xorCompressor.refresh();
        lastBetaStar = Integer.MAX_VALUE;
        numberOfValues = 0;
        Arrays.fill(frequency, 0);
        Arrays.fill(leadDistribution, 0);
        Arrays.fill(trailDistribution, 0);
    }

    @Override
    public int close(){
        calculateDistribution();
        compress();
        init();
        return out.track_bits();
    }


    @Override
    public int encode(double value) {
        addValue(value);
        if(numberOfValues == window){
            calculateDistribution();
            compress();
            init();
        }
        return out.track_bits();
    }
}
