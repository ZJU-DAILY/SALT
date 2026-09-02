package algorithms.SElfStar.decoder;

import algorithms.Decoder;
import algorithms.SElfStar.Elf64Utils;
import algorithms.SElfStar.Huffman.Code;
import algorithms.SElfStar.Huffman.HuffmanEncode;
import algorithms.SElfStar.Huffman.Node;
import algorithms.SElfStar.SElfStarXORDecompressor;

import java.util.Arrays;

public class DoubleSElfStarDecoder extends Decoder {
    private final SElfStarXORDecompressor xorDecompressor;
    private int lastBetaStar = Integer.MAX_VALUE;
    private int count =0;
    private int window = 1000;
    private final int[] frequency = new int[17];
    private boolean isFirstBlock = true;
    private Node root;

    public DoubleSElfStarDecoder(String inputPath) {
        super(inputPath);
        xorDecompressor = new SElfStarXORDecompressor(this.in);
    }

    public void refresh() {
        lastBetaStar = Integer.MAX_VALUE;
        xorDecompressor.refresh();
        isFirstBlock = false;
    }

    public Double nextValue() {
        if (!isFirstBlock) {
            return nextValueHuffman();
        } else {
            return nextValueFirst();
        }
    }

    public Double nextValueFirst() {
        Double v;
        if (readInt(1) == 0) {
            v = recoverVByBetaStar();               // case 0
            frequency[lastBetaStar]++;
        } else if (readInt(1) == 0) {
            v = xorDecompressor.readValue();        // case 10
            frequency[16]++;
        } else {
            lastBetaStar = readInt(4);          // case 11
            v = recoverVByBetaStar();
            frequency[lastBetaStar]++;
        }
        return v;
    }

    public Double nextValueHuffman() {
        Double v;
        Node current = root;
        while (true) {
            current = current.children[readInt(1)];
            if (current.data >= 0) {
                if (current.data != 16) {
                    lastBetaStar = current.data;
                    v = recoverVByBetaStar();
                    frequency[lastBetaStar]++;
                } else {
                    v = xorDecompressor.readValue();
                    frequency[16]++;
                }
                break;
            }
        }
        return v;
    }

    private Double recoverVByBetaStar() {
        double v;
        Double vPrime = xorDecompressor.readValue();
        int sp = Elf64Utils.getSP(Math.abs(vPrime));
        if (lastBetaStar == 0) {
            v = Elf64Utils.get10iN(-sp - 1);
            if (vPrime < 0) {
                v = -v;
            }
        } else {
            int alpha = lastBetaStar - sp - 1;
            v = Elf64Utils.roundUp(vPrime, alpha);
        }
        return v;
    }

    private int readInt(int len) {
        try {
            return in.readInt(len);
        } catch (Exception e) {
            throw new RuntimeException("IO error: " + e.getMessage());
        }
    }

    @Override
    public double decodeDouble() {
        double res = nextValue();
        count ++;
        if(count == window){
            Code[] huffmanCode = HuffmanEncode.getHuffmanCodes(frequency);
            root = HuffmanEncode.buildHuffmanTree(huffmanCode);
            Arrays.fill(frequency, 0);
            refresh();
            count = 0;
        }
        return res;
    }
}
