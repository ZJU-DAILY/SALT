package algorithms.SElfStar.encoder;

import algorithms.Encoder;
import algorithms.SElfStar.Elf64Utils;
import algorithms.SElfStar.SElfStarXORCompressor;
import algorithms.SElfStar.Huffman.Code;
import algorithms.SElfStar.Huffman.HuffmanEncode;

import java.util.Arrays;

// From https://github.com/Spatio-Temporal-Lab/SElfStar
public class DoubleSElfStarEncoder extends Encoder {
    private final SElfStarXORCompressor xorCompressor;
    private final int window = 1000;
    private int lastBetaStar = Integer.MAX_VALUE;
    private int numberOfValues = 0;
    private final int[] frequency = new int[17];    // 0 is for 10-i, 16 is for not erasing
    private Code[] huffmanCode;
    private boolean isFirstBlock = true;

    public DoubleSElfStarEncoder(String outputpath) {
        super(outputpath);
        xorCompressor = new SElfStarXORCompressor(this.out);
    }

    public void addValue(double v) {
        if (!isFirstBlock) {
            addValueHuffman(v);
        } else {
            addValueFirst(v);
        }
    }

    private void addValueFirst(double v) {
        long vLong = Double.doubleToRawLongBits(v);
        long vPrimeLong;
        numberOfValues++;

        if (v == 0.0 || Double.isInfinite(v)) {
            out.write(2, 2); // case 10
            vPrimeLong = vLong;
            frequency[16]++;
        } else if (Double.isNaN(v)) {
            out.write(2, 2); // case 10
            vPrimeLong = 0xfff8000000000000L & vLong;
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
                if (alphaAndBetaStar[1] == lastBetaStar) {
                    out.write(false);    // case 0
                } else {
                    out.write(alphaAndBetaStar[1] | 0x30, 6);  // case 11, 2 + 4 = 6
                    lastBetaStar = alphaAndBetaStar[1];
                }
                vPrimeLong = mask & vLong;
                frequency[alphaAndBetaStar[1]]++;
            } else {
                out.write(2, 2); // case 10
                vPrimeLong = vLong;
                frequency[16]++;
            }
        }
        xorCompressor.addValue(vPrimeLong);
    }

    private void addValueHuffman(double v) {
        long vLong = Double.doubleToRawLongBits(v);
        long vPrimeLong;
        numberOfValues++;

        if (v == 0.0 || Double.isInfinite(v)) {
            out.write(huffmanCode[16].code, huffmanCode[16].length); // not erase
            vPrimeLong = vLong;
            frequency[16]++;
        } else if (Double.isNaN(v)) {
            out.write(huffmanCode[16].code, huffmanCode[16].length); // not erase
            vPrimeLong = 0x7ff8000000000000L;
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
                out.write(huffmanCode[alphaAndBetaStar[1]].code, huffmanCode[alphaAndBetaStar[1]].length);  // case 11, 2 + 4 = 6
                lastBetaStar = alphaAndBetaStar[1];
                vPrimeLong = mask & vLong;
                frequency[alphaAndBetaStar[1]]++;
            } else {
                out.write(huffmanCode[16].code, huffmanCode[16].length); // not erase
                vPrimeLong = vLong;
                frequency[16]++;
            }
        }
        xorCompressor.addValue(vPrimeLong);
    }

    private void end(){
        if (isFirstBlock) {
//            out.write(2, 2);  // case 10
            isFirstBlock = false;
        }
//        else {
//            out.write(huffmanCode[16].code, huffmanCode[16].length); // not erase
//        }

        huffmanCode = HuffmanEncode.getHuffmanCodes(frequency);
        Arrays.fill(frequency, 0);
        xorCompressor.close();
    }

    private void refresh(){
        lastBetaStar = Integer.MAX_VALUE;
        numberOfValues = 0;

        xorCompressor.refresh();        // note this refresh should be at the last
    }

    @Override
    public int encode(double value) {
        addValue(value);
        if(numberOfValues == window) {
            end();
            refresh();
        }
        return out.track_bits();
    }
}
