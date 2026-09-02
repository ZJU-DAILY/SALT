package utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class StreamWriter {
    private final int bufferSize = 1024;
    private byte[] buffer = new byte[bufferSize];
    private int pointer = 0;
    private int currentByte = 0;
    private int leftBits = 8;

    private int delta_bits = 0;

    private boolean append = false;
    private final String fileName;

    public StreamWriter(String fileName) {
        this.fileName = fileName;
    }

    private void writeToDisk(byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(fileName, append)) {
            append = true;
            fos.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveByte() {
        if (leftBits == 8) return;
        currentByte <<= leftBits; // padding with 0
        buffer[pointer++] = (byte) currentByte;
        leftBits = 8;
        currentByte = 0;
        if (pointer >= bufferSize) {
            writeToDisk(buffer);
            pointer = 0;
        }
    }

    private void init() {
        leftBits = 8;
        currentByte = 0;
        pointer = 0;
    }


    public int track_bits() {
        int b = delta_bits;
        this.delta_bits = 0;
        return b;
    }

    public void clear() {
        if (pointer == 0 && currentByte == 0 && leftBits == 8) return;
        saveByte();
        byte[] data = Arrays.copyOf(buffer, pointer);
        writeToDisk(data);
        init();
    }


    public void write(boolean b) {
        delta_bits += 1; // written bits
        leftBits--;
        currentByte = (currentByte << 1) | (b ? 1 : 0);
        if (leftBits == 0) saveByte();
    }

    public void write(long value, int size) {
        delta_bits += Math.max(0, size); // written bits
        while (size > 0) {
            int len = Math.min(leftBits, size);
            int mask = (1 << len) - 1;
            currentByte <<= len;
            currentByte |= (byte) ((value >> (size - len)) & mask);
            leftBits -= len;
            if (leftBits == 0) saveByte();
            size -= len;
        }
    }

    public void write(int value, int size) {
        write((long) value, size);
    }

    public void write(float value, int size) {
        write((long) Float.floatToRawIntBits(value), size);
    }

    public void write(double value, int size) {
        write(Double.doubleToRawLongBits(value), size);
    }
}
