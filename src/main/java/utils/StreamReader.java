package utils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class StreamReader {
    private int bufferSize = 1024;
    private byte[] buffer = new byte[bufferSize];
    private int cachedBytes = 0;
    private int pointer = 0;
    private int leftBits = 8;

    private long breakpoint = 0;

    private String fileName;

    public StreamReader(String fileName) {
        this.fileName = fileName;
        cacheData();
    }

    public void init() {
        leftBits = 8;
        pointer = 0;
    }

    private void cacheData(){
        try (FileInputStream fis = new FileInputStream(fileName)) {
            fis.skip(breakpoint);
            cachedBytes = fis.read(buffer);
            breakpoint += cachedBytes;
            init();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    public long readLong(int size) {
        long res = 0;
        while (size >= leftBits) {
            int mask = (1 << leftBits) - 1;
            res = (res << leftBits) | (buffer[pointer] & mask);
            size -= leftBits;
            pointer++;
            if (pointer >= cachedBytes) {
                try {
                    cacheData();
                } catch (Exception e) { //EOF
                    return res;
                }
            }
            leftBits = 8;
        }
        if (size > 0) {
            leftBits -= size;
            int mask = (1 << size) - 1;
            res = (res << size) | ((buffer[pointer] >> leftBits) & mask);
        }
        return res;
    }

    public int readInt(int size) {
        return (int) readLong(size);
    }

    public float readFloat(int size) {
        return Float.floatToIntBits((int) readLong(size));
    }

    public double readDouble(int size) {
        return Double.longBitsToDouble(readLong(size));
    }

    public boolean readBoolean() {
        return readLong(1) > 0;
    }
}
