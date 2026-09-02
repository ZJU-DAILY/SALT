package algorithms.ElfStar.decoder;

import algorithms.Decoder;
import algorithms.ElfStar.Elf64Utils;
import algorithms.ElfStar.ElfStarXORDecompressor;
import algorithms.ElfStar.Huffman.Code;
import algorithms.ElfStar.Huffman.HuffmanEncode;
import algorithms.ElfStar.Huffman.Node;

public class DoubleElfStarDecoder extends Decoder {
    private static Code[] huffmanCode = new Code[17];
    private final ElfStarXORDecompressor xorDecompressor;
    private int lastBetaStar = Integer.MAX_VALUE;
    private Node root;
    private int window = 1000;
    private double[] buffer = new double[window];
    private int available = 0;

    public DoubleElfStarDecoder(String inputPath) {
        super(inputPath);
        xorDecompressor = new ElfStarXORDecompressor(this.in);
    }

    public void decompress() {
        initHuffmanTree();
        Double value;
        for (int i = 0; i < window; i++) {
            buffer[i] = nextValue();
        }
        available = window;
    }

    private void initHuffmanTree() {
        HuffmanEncode.readHuffmanCodes(in, huffmanCode);
        root = HuffmanEncode.buildHuffmanTree(huffmanCode);
    }


    public Double nextValue() {
        Double v;
        Node current = root;
        while (true) {
            current = current.children[readInt(1)];
            if (current.data >= 0) {
                if (current.data != 16) {
                    lastBetaStar = current.data;
                    v = recoverVByBetaStar();
                } else {
                    v = xorDecompressor.readValue();
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

    private void init(){
        lastBetaStar = Integer.MAX_VALUE;
        xorDecompressor.refresh();
        huffmanCode = new Code[17];
    }

    @Override
    public double decodeDouble() {
        if (available == 0) {
            decompress();
            init();
        }
        double res = buffer[window - available];
        available--;
        return res;
    }
}
