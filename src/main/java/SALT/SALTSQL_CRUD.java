package SALT;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;

import static SALT.SALTSQL.encodeAsOneWindow01String;
import static SALT.SALTSQL_Decompress.decompressOneWindow01Stream;
import static SALT.SALTSQL_Decompress.aggregateOneWindow01Stream;
/**
 * Packed-bits ONLY version:
 * - Main data: <base>.bin (bits packed MSB-first), starts with a 12-bit header (WIN + EXP + ULP), then window bits.
 * - Sidecars: <base>_window_len.bin / <base>_len_fenwick.bin / <base>_window_num.bin / <base>_num_fenwick.bin
 *   All are packed MSB-first, start with 5-byte meta (width + count), then values stream.
 *
 * This class returns window bits as a String of '0'/'1'.
 */
public final class SALTSQL_CRUD implements Closeable {

    // ---- sidecar data (window len/num kept in memory for fast access) ----
    private final long[] windowLenBits;   // n
    private final long[] windowNum;       // n

    // ---- sidecar files (packed, fixed-width, MSB-first) ----
    // window length / count arrays
    private final RandomAccessFile rafLen;     // <base>_window_len.bin (rw)
    private final RandomAccessFile rafNum;     // <base>_window_num.bin (rw)
    private final SidecarMeta lenMeta;         // width/count for window_len
    private final SidecarMeta numMeta;         // width/count for window_num

    // fenwick trees stored on disk (NOT loaded into memory)
    private final RandomAccessFile rafLenFen;  // <base>_len_fenwick.bin (rw)
    private final RandomAccessFile rafNumFen;  // <base>_num_fenwick.bin (rw)
    private final SidecarMeta lenFenMeta;      // width/count for len_fenwick (count == n)
    private final SidecarMeta numFenMeta;      // width/count for num_fenwick (count == n)

    // ---- main packed-bits file ----
    private final RandomAccessFile rafBin;
    private final int winBitsFromFile; // from main .bin 12-bit header (WIN_BITS)
    private static final long DATA_START_BIT_OFFSET = 12L; // main .bin has 12-bit metadata header; skip it

    private final int exponentBitsFromFile; // EXPONENT_BITS (4 bits)
    private final int ulpBitsFromFile;      // ULP_BITS (4 bits)

    public int getExponentBitsFromFile() { return exponentBitsFromFile; }
    public int getUlpBitsFromFile() { return ulpBitsFromFile; }

    private static int[] readMainMeta12(RandomAccessFile raf) throws IOException {
        long oldPos = raf.getFilePointer();
        try {
            raf.seek(0);

            int b0 = raf.read();
            int b1 = raf.read();
            if (b0 < 0 || b1 < 0) {
                throw new EOFException("Main bin too short to contain 12-bit meta");
            }

            // MSB-first packed:
            // b0 high4: WIN_BITS
            // b0 low4 : EXPONENT_BITS
            // b1 high4: ULP_BITS
            int winBits = ((b0 & 0xFF) >>> 4) & 0x0F;
            int expBits = (b0 & 0x0F);
            int ulpBits = ((b1 & 0xFF) >>> 4) & 0x0F;

            return new int[]{winBits, expBits, ulpBits};
        } finally {
            raf.seek(oldPos);
        }
    }

    private SALTSQL_CRUD(File bin, File len, File lenFen, File num, File numFen) throws IOException {
        RandomAccessFile tmpBin = null;
        RandomAccessFile tmpLen = null;
        RandomAccessFile tmpNum = null;
        RandomAccessFile tmpLenFen = null;
        RandomAccessFile tmpNumFen = null;

        try {
            // ---- window_len ----
            tmpLen = new RandomAccessFile(len, "rw");
            this.lenMeta = readSidecarMeta5(tmpLen, len.getName());
            this.windowLenBits = readSidecarValuesLong(tmpLen, this.lenMeta);

            // ---- window_num ----
            tmpNum = new RandomAccessFile(num, "rw");
            this.numMeta = readSidecarMeta5(tmpNum, num.getName());
            this.windowNum = readSidecarValuesLong(tmpNum, this.numMeta);

            int n = windowLenBits.length;
            if (windowNum.length != n) {
                throw new IOException("Sidecar size mismatch. windowLen=" + n +
                        ", windowNum=" + windowNum.length);
            }

            // ---- fenwick sidecars (on disk) ----
            tmpLenFen = new RandomAccessFile(lenFen, "rw");
            this.lenFenMeta = readSidecarMeta5(tmpLenFen, lenFen.getName());

            tmpNumFen = new RandomAccessFile(numFen, "rw");
            this.numFenMeta = readSidecarMeta5(tmpNumFen, numFen.getName());

            if (lenFenMeta.count != n || numFenMeta.count != n) {
                throw new IOException("Fenwick sidecar size mismatch. windowCount=" + n +
                        ", lenFen.count=" + lenFenMeta.count +
                        ", numFen.count=" + numFenMeta.count);
            }

            // ---- main .bin ----
            tmpBin = new RandomAccessFile(bin, "rw");

            // 读主文件前 12 bit meta：WIN(4) + EXP(4) + ULP(4)
            int[] meta = readMainMeta12(tmpBin);
            // WIN_BITS 不用读取/不使用：meta[0] 丢弃
            this.winBitsFromFile = meta[0];
            this.exponentBitsFromFile = meta[1];
            this.ulpBitsFromFile = meta[2];

            // assign raf fields only after all checks pass
            this.rafBin = tmpBin;
            this.rafLen = tmpLen;
            this.rafNum = tmpNum;
            this.rafLenFen = tmpLenFen;
            this.rafNumFen = tmpNumFen;

        } catch (IOException e) {
            safeClose(tmpBin);
            safeClose(tmpLen);
            safeClose(tmpNum);
            safeClose(tmpLenFen);
            safeClose(tmpNumFen);
            throw e;
        }
    }

    /**
     * ✅ 只输入一个文件名称即可初始化：
     * - 支持输入基名：Basel-temp
     * - 支持输入带路径/扩展名：./data/Basel-temp.csv 或 ./data/Basel-temp.bin
     *
     * 会自动拼接：
     *  base.bin
     *  base_window_len.bin
     *  base_len_fenwick.bin
     *  base_window_num.bin
     *  base_num_fenwick.bin
     */
    public static SALTSQL_CRUD openByName(String fileNameOrPath) throws IOException {
        File any = new File(fileNameOrPath);
        File dir = any.getParentFile();
        if (dir == null) dir = new File(".");

        String name = any.getName();
        String base = removeExtension(name);

        File bin = new File(dir, base + ".bin");
        File len = new File(dir, base + "_window_len.bin");
        File lenFen = new File(dir, base + "_len_fenwick.bin");
        File num = new File(dir, base + "_window_num.bin");
        File numFen = new File(dir, base + "_num_fenwick.bin");

        // Open the main file and sidecars.
        SALTSQL_CRUD crud = new SALTSQL_CRUD(bin, len, lenFen, num, numFen);

        // Read the 12-bit header: WIN(4) + EXP(4) + ULP(4).
        long oldPos = crud.rafBin.getFilePointer();
        try {
            crud.rafBin.seek(0);

            int b0 = crud.rafBin.read();
            int b1 = crud.rafBin.read();
            if (b0 < 0 || b1 < 0) {
                throw new EOFException("Main bin too short to contain 12-bit meta: " + bin.getName());
            }

        } finally {
            crud.rafBin.seek(oldPos);
        }

        return crud;
    }

    /**
     * 读取窗口w对应的纯01（无换行）
     * windowNo 为 0-based：0表示第一个窗口
     */
    public String readWindow01(int windowNo) throws IOException {
        checkWindowNo(windowNo);

        long bitsBefore = fenwickPrefixSumFromFile(rafLenFen, lenFenMeta, windowNo); // sum(len[0..w-1])
        long bitLen = windowLenBits[windowNo];

        long bitOffset = DATA_START_BIT_OFFSET + bitsBefore;
        return readPackedBits01(bitOffset, bitLen);
    }

    // -------------------- Fenwick prefix sum using stored tree --------------------
    private static long fenwickPrefixSum(long[] fenwickTree1Indexed, int k) {
        // sum of first k elements (0..k-1) where fenwickTree is 1-indexed
        long res = 0L;
        int idx = k;
        while (idx > 0) {
            res += fenwickTree1Indexed[idx];
            idx -= idx & -idx;
        }
        return res;
    }

    // -------------------- Packed bits random access reader (MSB-first) --------------------
    private String readPackedBits01(long bitOffset, long bitLen) throws IOException {
        if (bitLen < 0) throw new IllegalArgumentException("bitLen < 0");
        if (bitLen > Integer.MAX_VALUE) throw new IOException("Window too large: " + bitLen);

        int n = (int) bitLen;
        StringBuilder sb = new StringBuilder(n);

        long startByte = bitOffset >>> 3;       // / 8
        int startBitInByte = (int) (bitOffset & 7L);

        long totalBits = (long) startBitInByte + bitLen;
        int bytesToRead = (int) ((totalBits + 7L) >>> 3);

        byte[] buf = new byte[bytesToRead];
        rafBin.seek(startByte);
        rafBin.readFully(buf);

        for (int i = 0; i < n; i++) {
            int bi = startBitInByte + i;
            int byteIndex = bi >>> 3;
            int bitIndex = 7 - (bi & 7); // MSB-first
            int bit = (buf[byteIndex] >>> bitIndex) & 1;
            sb.append(bit == 0 ? '0' : '1');
        }
        return sb.toString();
    }

    // -------------------- Sidecar readers (packed bits ONLY) --------------------
    private static final class PackedBitInput implements Closeable {
        private final InputStream in;
        private int curByte = -1;
        private int bitsLeft = 0; // 0..8

        PackedBitInput(InputStream in) {
            this.in = in;
        }

        int readBit() throws IOException {
            if (bitsLeft == 0) {
                curByte = in.read();
                if (curByte < 0) throw new EOFException("Unexpected EOF while reading packed bits");
                bitsLeft = 8;
            }
            int bit = (curByte >>> (bitsLeft - 1)) & 1; // MSB-first
            bitsLeft--;
            return bit;
        }

        long readBitsLong(int bits) throws IOException {
            if (bits < 0 || bits > 63) {
                throw new IOException("Unsupported bit width: " + bits + " (expected 0..63)");
            }
            long v = 0L;
            for (int i = 0; i < bits; i++) {
                v = (v << 1) | readBit();
            }
            return v;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class SidecarMeta {
        final int width; // value bit-width
        final int count; // number of values

        SidecarMeta(int width, int count) {
            this.width = width;
            this.count = count;
        }
    }

    private static void safeClose(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignore) {}
    }

    // ---------- packed sidecar random access helpers (MSB-first, fixed width) ----------

    // RandomAccessFile version: reads 5-byte header (width + count) at offset 0.
    private static SidecarMeta readSidecarMeta5(RandomAccessFile raf, String name) throws IOException {
        long oldPos = raf.getFilePointer();
        try {
            raf.seek(0);
            int width = raf.readUnsignedByte();
            int count = raf.readInt(); // big-endian

            if (width <= 0 || width > 63) throw new IOException("Bad width in " + name + ": " + width);
            if (count < 0) throw new IOException("Bad count in " + name + ": " + count);
            return new SidecarMeta(width, count);
        } finally {
            raf.seek(oldPos);
        }
    }

    // Reads ALL packed values (count items, each width bits) into memory.
    // This is used for window_len/window_num only (not for fenwick trees).
    private static long[] readSidecarValuesLong(RandomAccessFile raf, SidecarMeta meta) throws IOException {
        long totalBits = (long) meta.count * (long) meta.width;
        long totalBytesL = (totalBits + 7L) >>> 3;
        if (totalBytesL > Integer.MAX_VALUE) {
            throw new IOException("Sidecar too large to load: bytes=" + totalBytesL);
        }
        int totalBytes = (int) totalBytesL;

        byte[] buf = new byte[totalBytes];
        raf.seek(5L); // skip header (1+4 bytes)
        raf.readFully(buf);

        long[] out = new long[meta.count];
        long bitPos = 0L;
        for (int i = 0; i < meta.count; i++) {
            out[i] = readBitsFromBuffer(buf, bitPos, meta.width);
            bitPos += meta.width;
        }
        return out;
    }

    private static long readBitsFromBuffer(byte[] buf, long startBit, int bits) throws IOException {
        if (bits < 0 || bits > 63) throw new IOException("Unsupported bit width: " + bits);
        long v = 0L;
        for (int i = 0; i < bits; i++) {
            long b = startBit + i;
            int byteIndex = (int) (b >>> 3);
            int bitInByte = (int) (b & 7); // 0..7 from MSB
            int bit = (buf[byteIndex] >>> (7 - bitInByte)) & 1;
            v = (v << 1) | bit;
        }
        return v;
    }

    private static long maxForWidth(int width) {
        // width in 1..63
        return ~(-1L << width);
    }

    private static void ensureFitsWidth(int width, long value, String what) throws IOException {
        if (value < 0) throw new IOException(what + " must be >= 0, got " + value);
        long max = maxForWidth(width);
        if (value > max) throw new IOException(what + " overflow: value=" + value + " > max(" + width + " bits)=" + max);
    }

    // Read bits from arbitrary bit offset in a file (MSB-first in each byte).
    private static long readPackedBitsLongAt(RandomAccessFile raf, long startBit, int bits) throws IOException {
        if (bits < 0 || bits > 63) throw new IOException("Unsupported bit width: " + bits);

        long startByte = startBit >>> 3;
        int bitInByte = (int) (startBit & 7);

        int totalBits = bitInByte + bits;
        int needBytes = (totalBits + 7) >>> 3;

        byte[] buf = new byte[needBytes];
        raf.seek(startByte);
        raf.readFully(buf);

        long v = 0L;
        for (int i = 0; i < bits; i++) {
            int bitPos = bitInByte + i;
            int bIndex = bitPos >>> 3;
            int inByte = bitPos & 7;
            int bit = (buf[bIndex] >>> (7 - inByte)) & 1;
            v = (v << 1) | bit;
        }
        return v;
    }

    // Write bits to arbitrary bit offset in a file (MSB-first in each byte).
    private static void writePackedBitsLongAt(RandomAccessFile raf, long startBit, int bits, long value) throws IOException {
        if (bits < 0 || bits > 63) throw new IOException("Unsupported bit width: " + bits);
        ensureFitsWidth(bits == 0 ? 1 : bits, value, "packed value"); // bits==0 not expected

        long startByte = startBit >>> 3;
        long endBitExclusive = startBit + bits;
        long endByte = (endBitExclusive + 7L) >>> 3; // exclusive
        int len = (int) (endByte - startByte);

        byte[] buf = new byte[len];
        raf.seek(startByte);
        raf.readFully(buf);

        for (int i = 0; i < bits; i++) {
            long absBit = startBit + i;
            int relByte = (int) ((absBit >>> 3) - startByte);
            int bitInByte = (int) (absBit & 7);
            int mask = 1 << (7 - bitInByte);

            int bit = (int) ((value >>> (bits - 1 - i)) & 1L);
            if (bit == 1) {
                buf[relByte] = (byte) (buf[relByte] | mask);
            } else {
                buf[relByte] = (byte) (buf[relByte] & ~mask);
            }
        }

        raf.seek(startByte);
        raf.write(buf);
    }

    // Sidecar value access (1-based idx1)
    private static long readSidecarValueAt(RandomAccessFile raf, SidecarMeta meta, int idx1) throws IOException {
        if (idx1 <= 0 || idx1 > meta.count) {
            throw new IndexOutOfBoundsException("sidecar index out of range: " + idx1 + " (1.." + meta.count + ")");
        }
        long startBit = 40L + (long) (idx1 - 1) * (long) meta.width;
        return readPackedBitsLongAt(raf, startBit, meta.width);
    }

    private static void writeSidecarValueAt(RandomAccessFile raf, SidecarMeta meta, int idx1, long value) throws IOException {
        if (idx1 <= 0 || idx1 > meta.count) {
            throw new IndexOutOfBoundsException("sidecar index out of range: " + idx1 + " (1.." + meta.count + ")");
        }
        ensureFitsWidth(meta.width, value, "sidecar value");
        long startBit = 40L + (long) (idx1 - 1) * (long) meta.width;
        writePackedBitsLongAt(raf, startBit, meta.width, value);
    }

    // ---------- Fenwick (on-disk) operations ----------
    // Fenwick sidecar stores tree[1..n] as count=n values (1-indexed). (Index 0 is implicit 0.)
    private static long fenwickPrefixSumFromFile(RandomAccessFile rafFen, SidecarMeta fenMeta, int idx) throws IOException {
        long sum = 0L;
        for (int i = idx; i > 0; i -= i & -i) {
            sum += readSidecarValueAt(rafFen, fenMeta, i);
        }
        return sum;
    }

    // Return smallest idx (1..n) such that prefixSum(idx) >= target
    private static int fenwickLowerBoundFromFile(RandomAccessFile rafFen, SidecarMeta fenMeta, long target) throws IOException {
        int n = fenMeta.count;
        int idx = 0;

        int bitMask = Integer.highestOneBit(n);
        long sum = 0L;

        for (int step = bitMask; step != 0; step >>>= 1) {
            int next = idx + step;
            if (next <= n) {
                long nodeVal = readSidecarValueAt(rafFen, fenMeta, next);
                if (sum + nodeVal < target) {
                    sum += nodeVal;
                    idx = next;
                }
            }
        }
        return idx + 1;
    }

    // Fenwick point add: tree[i] += delta for all affected nodes (i = idx1, idx1+=lowbit(idx1)...)
    private static void fenwickAddToFile(RandomAccessFile rafFen, SidecarMeta fenMeta, int idx1, long delta) throws IOException {
        int n = fenMeta.count;
        for (int i = idx1; i <= n; i += i & -i) {
            long cur = readSidecarValueAt(rafFen, fenMeta, i);
            long next = cur + delta;
            if (next < 0) {
                throw new IOException("Fenwick node underflow at i=" + i + ": " + cur + " + (" + delta + ") < 0");
            }
            ensureFitsWidth(fenMeta.width, next, "fenwick node");
            writeSidecarValueAt(rafFen, fenMeta, i, next);
        }
    }

    private static byte[] readFullyN(InputStream in, int n, String eofMessage) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException(eofMessage);
            off += r;
        }
        return buf;
    }

    private static SidecarMeta readSidecarMeta5(InputStream in, String name) throws IOException {
        byte[] meta5 = readFullyN(in, 5, "Bad meta in " + name);

        int width = meta5[0] & 0xFF;
        int count =
                ((meta5[1] & 0xFF) << 24) |
                        ((meta5[2] & 0xFF) << 16) |
                        ((meta5[3] & 0xFF) << 8)  |
                        (meta5[4] & 0xFF);

        if (width <= 0 || width > 63) throw new IOException("Bad width in " + name + ": " + width);
        if (count < 0) throw new IOException("Bad count in " + name + ": " + count);

        return new SidecarMeta(width, count);
    }

    public static long[] readLenSidecarPacked(File lenFile) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(lenFile))) {
            SidecarMeta meta = readSidecarMeta5(raw, lenFile.getName());

            long[] vals = new long[meta.count];
            try (PackedBitInput p = new PackedBitInput(raw)) {
                for (int i = 0; i < meta.count; i++) {
                    vals[i] = p.readBitsLong(meta.width);
                }
            }
            return vals;
        }
    }

    public static long[] readFenwickSidecarPacked(File fenwickFile) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(fenwickFile))) {
            SidecarMeta meta = readSidecarMeta5(raw, fenwickFile.getName());

            long[] bit = new long[meta.count + 1];
            bit[0] = 0L;

            try (PackedBitInput p = new PackedBitInput(raw)) {
                for (int i = 1; i <= meta.count; i++) {
                    bit[i] = p.readBitsLong(meta.width);
                }
            }
            return bit;
        }
    }

    private static String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }

    private void checkWindowNo(int w) {
        if (w < 0 || w >= windowLenBits.length) {
            throw new IllegalArgumentException("windowNo out of range: " + w +
                    " (0.." + (windowLenBits.length - 1) + ")");
        }
    }

    /**
     * Core update method (single-window):
     * Updates BOTH windowLenBits/windowNum and their on-disk sidecars, and performs
     * Fenwick point updates on-disk (O(log N) nodes each).
     *
     * @param windowNo 0-based window index
     * @param newLenBits new bit length for this window (>=0)
     * @param newNum new record count for this window (>=0)
     */
    public synchronized void updateWindowLenAndNum(int windowNo, long newLenBits, long newNum) throws IOException {
        checkWindowNo(windowNo);
        if (newLenBits < 0) throw new IllegalArgumentException("newLenBits must be >= 0");
        if (newNum < 0) throw new IllegalArgumentException("newNum must be >= 0");

        long oldLen = windowLenBits[windowNo];
        long oldNum = windowNum[windowNo];

        long deltaLen = newLenBits - oldLen;
        long deltaNum = newNum - oldNum;

        if (deltaLen == 0 && deltaNum == 0) return;

        int idx1 = windowNo + 1; // 1-based for sidecars & fenwick

        // 1) update fenwick trees on disk (affects prefix sums)
        if (deltaLen != 0) {
            fenwickAddToFile(rafLenFen, lenFenMeta, idx1, deltaLen);
        }
        if (deltaNum != 0) {
            fenwickAddToFile(rafNumFen, numFenMeta, idx1, deltaNum);
        }

        // 2) write back window_len/window_num values (sidecars on disk)
        if (deltaLen != 0) {
            writeSidecarValueAt(rafLen, lenMeta, idx1, newLenBits);
        }
        if (deltaNum != 0) {
            writeSidecarValueAt(rafNum, numMeta, idx1, newNum);
        }

        // 3) finally update in-memory arrays
        if (deltaLen != 0) windowLenBits[windowNo] = newLenBits;
        if (deltaNum != 0) windowNum[windowNo] = newNum;
    }
    @Override
    public void close() throws IOException {
        IOException ex = null;
        try { rafBin.close(); } catch (IOException e) { ex = e; }
        try { rafLen.close(); } catch (IOException e) { if (ex == null) ex = e; }
        try { rafNum.close(); } catch (IOException e) { if (ex == null) ex = e; }
        try { rafLenFen.close(); } catch (IOException e) { if (ex == null) ex = e; }
        try { rafNumFen.close(); } catch (IOException e) { if (ex == null) ex = e; }
        if (ex != null) throw ex;
    }

    public static BigDecimal getValueByRecordIndex(SALTSQL_CRUD crud, long recordIndex) throws IOException {
        if (crud == null) throw new IllegalArgumentException("crud is null");
        return crud.getValueByRecordIndexInternal(recordIndex);
    }

    /** Returns the current logical number of records represented by the index. */
    public synchronized long getTotalRecords() throws IOException {
        return fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNum.length);
    }

    /**
     * Materializes a contiguous logical range without decompressing the whole column.
     * Only windows intersecting [startInclusive, startInclusive + length) are decoded.
     */
    public synchronized List<BigDecimal> getRangeByRecordIndex(long startInclusive, int length)
            throws IOException {
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");

        long totalRecords = getTotalRecords();
        if (startInclusive < 0 || startInclusive > totalRecords - (long) length) {
            throw new IllegalArgumentException("range out of bounds: start=" + startInclusive
                    + ", length=" + length + ", totalRecords=" + totalRecords);
        }
        if (length == 0) return new java.util.ArrayList<BigDecimal>(0);

        long target = startInclusive + 1;
        int windowNo = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target) - 1;
        long recordsBeforeWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (startInclusive - recordsBeforeWindow);

        java.util.ArrayList<BigDecimal> result = new java.util.ArrayList<BigDecimal>(length);
        int remaining = length;

        while (remaining > 0) {
            while (windowNo < windowNum.length && windowNum[windowNo] == 0) {
                windowNo++;
                inWindowIndex = 0;
            }
            if (windowNo >= windowNum.length) {
                throw new EOFException("range index reached the end before " + length + " values were read");
            }

            String bits = readWindow01(windowNo);
            List<BigDecimal> values = decompressOneWindow01Stream(
                    bits, exponentBitsFromFile, ulpBitsFromFile);
            if (values.size() != windowNum[windowNo]) {
                throw new IOException("Decompressed size mismatch: windowNo=" + windowNo
                        + ", values.size=" + values.size()
                        + ", window_num=" + windowNum[windowNo]);
            }
            if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
                throw new IOException("Range index mismatch: windowNo=" + windowNo
                        + ", inWindowIndex=" + inWindowIndex
                        + ", values.size=" + values.size());
            }

            int take = Math.min(remaining, values.size() - inWindowIndex);
            result.addAll(values.subList(inWindowIndex, inWindowIndex + take));
            remaining -= take;
            windowNo++;
            inWindowIndex = 0;
        }

        return result;
    }

    /**
     * Returns a contiguous logical range as primitive doubles. This is the query
     * benchmark API: Raw, SALT+, and every baseline therefore materialize the same
     * result type and the same number of returned values.
     */
    public synchronized double[] getRangeAsDoublesByRecordIndex(long startInclusive, int length)
            throws IOException {
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");

        long totalRecords = getTotalRecords();
        if (startInclusive < 0 || startInclusive > totalRecords - (long) length) {
            throw new IllegalArgumentException("range out of bounds: start=" + startInclusive
                    + ", length=" + length + ", totalRecords=" + totalRecords);
        }
        double[] result = new double[length];
        if (length == 0) return result;

        long target = startInclusive + 1;
        int windowNo = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target) - 1;
        long recordsBeforeWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (startInclusive - recordsBeforeWindow);
        int remaining = length;
        int outputIndex = 0;

        while (remaining > 0) {
            while (windowNo < windowNum.length && windowNum[windowNo] == 0) {
                windowNo++;
                inWindowIndex = 0;
            }
            if (windowNo >= windowNum.length) {
                throw new EOFException("range index reached the end before " + length + " values were read");
            }

            String bits = readWindow01(windowNo);
            List<BigDecimal> values = decompressOneWindow01Stream(
                    bits, exponentBitsFromFile, ulpBitsFromFile);
            if (values.size() != windowNum[windowNo]) {
                throw new IOException("Decompressed size mismatch: windowNo=" + windowNo
                        + ", values.size=" + values.size()
                        + ", window_num=" + windowNum[windowNo]);
            }
            if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
                throw new IOException("Range index mismatch: windowNo=" + windowNo
                        + ", inWindowIndex=" + inWindowIndex
                        + ", values.size=" + values.size());
            }

            int take = Math.min(remaining, values.size() - inWindowIndex);
            int end = inWindowIndex + take;
            for (int i = inWindowIndex; i < end; i++) {
                result[outputIndex++] = values.get(i).doubleValue();
            }
            remaining -= take;
            windowNo++;
            inWindowIndex = 0;
        }

        return result;
    }

    /**
     * Streams a contiguous logical range to {@code consumer} without materializing the
     * complete range. Only intersecting compressed windows are decoded, in index order.
     */
    public synchronized void scanRangeByRecordIndex(long startInclusive, int length,
                                                     DoubleConsumer consumer)
            throws IOException {
        if (consumer == null) throw new IllegalArgumentException("consumer is null");
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");

        long totalRecords = getTotalRecords();
        if (startInclusive < 0 || startInclusive > totalRecords - (long) length) {
            throw new IllegalArgumentException("range out of bounds: start=" + startInclusive
                    + ", length=" + length + ", totalRecords=" + totalRecords);
        }
        if (length == 0) return;

        long target = startInclusive + 1;
        int windowNo = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target) - 1;
        long recordsBeforeWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (startInclusive - recordsBeforeWindow);
        int remaining = length;

        while (remaining > 0) {
            while (windowNo < windowNum.length && windowNum[windowNo] == 0) {
                windowNo++;
                inWindowIndex = 0;
            }
            if (windowNo >= windowNum.length) {
                throw new EOFException("scan reached the end before " + length + " values were read");
            }

            String bits = readWindow01(windowNo);
            List<BigDecimal> values = decompressOneWindow01Stream(
                    bits, exponentBitsFromFile, ulpBitsFromFile);
            if (values.size() != windowNum[windowNo]) {
                throw new IOException("Decompressed size mismatch: windowNo=" + windowNo
                        + ", values.size=" + values.size()
                        + ", window_num=" + windowNum[windowNo]);
            }
            if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
                throw new IOException("Scan index mismatch: windowNo=" + windowNo
                        + ", inWindowIndex=" + inWindowIndex
                        + ", values.size=" + values.size());
            }

            int take = Math.min(remaining, values.size() - inWindowIndex);
            int end = inWindowIndex + take;
            for (int i = inWindowIndex; i < end; i++) {
                consumer.accept(values.get(i).doubleValue());
            }
            remaining -= take;
            windowNo++;
            inWindowIndex = 0;
        }
    }

    /** Computes SUM over a logical range by decoding only intersecting windows. */
    public synchronized double sumRangeByRecordIndex(long startInclusive, int length) throws IOException {
        return aggregateRangeByRecordIndex(startInclusive, length, 0);
    }

    /** Computes AVG over a logical range by decoding only intersecting windows. */
    public synchronized double avgRangeByRecordIndex(long startInclusive, int length) throws IOException {
        return aggregateRangeByRecordIndex(startInclusive, length, 1);
    }

    /** Computes MIN over a logical range by decoding only intersecting windows. */
    public synchronized double minRangeByRecordIndex(long startInclusive, int length) throws IOException {
        return aggregateRangeByRecordIndex(startInclusive, length, 2);
    }

    /** Computes MAX over a logical range by decoding only intersecting windows. */
    public synchronized double maxRangeByRecordIndex(long startInclusive, int length) throws IOException {
        return aggregateRangeByRecordIndex(startInclusive, length, 3);
    }

    private double aggregateRangeByRecordIndex(long startInclusive, int length, int operation)
            throws IOException {
        if (length <= 0) throw new IllegalArgumentException("length must be > 0");

        long totalRecords = getTotalRecords();
        if (startInclusive < 0 || startInclusive > totalRecords - (long) length) {
            throw new IllegalArgumentException("range out of bounds: start=" + startInclusive
                    + ", length=" + length + ", totalRecords=" + totalRecords);
        }

        long target = startInclusive + 1;
        int windowNo = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target) - 1;
        long recordsBeforeWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (startInclusive - recordsBeforeWindow);
        int remaining = length;
        double aggregate = operation == 2 ? Double.POSITIVE_INFINITY
                : operation == 3 ? Double.NEGATIVE_INFINITY : 0.0;

        while (remaining > 0) {
            while (windowNo < windowNum.length && windowNum[windowNo] == 0) {
                windowNo++;
                inWindowIndex = 0;
            }
            if (windowNo >= windowNum.length) {
                throw new EOFException("aggregate range reached the end before " + length + " values were read");
            }

            int windowRecords = (int) windowNum[windowNo];
            if (inWindowIndex < 0 || inWindowIndex >= windowRecords) {
                throw new IOException("Aggregate index mismatch: windowNo=" + windowNo
                        + ", inWindowIndex=" + inWindowIndex
                        + ", window_num=" + windowRecords);
            }
            int take = Math.min(remaining, windowRecords - inWindowIndex);
            String bits = readWindow01(windowNo);
            SALTSQL_Decompress.WindowAggregateResult windowResult =
                    aggregateOneWindow01Stream(bits, exponentBitsFromFile, ulpBitsFromFile,
                            inWindowIndex, inWindowIndex + take, operation);
            if (windowResult.decodedCount != windowRecords) {
                throw new IOException("Decompressed size mismatch: windowNo=" + windowNo
                        + ", decodedCount=" + windowResult.decodedCount
                        + ", window_num=" + windowRecords);
            }
            if (windowResult.selectedCount != take) {
                throw new IOException("Aggregate selection mismatch: windowNo=" + windowNo
                        + ", selectedCount=" + windowResult.selectedCount
                        + ", expected=" + take);
            }
            if (operation == 0 || operation == 1) aggregate += windowResult.aggregate;
            else if (operation == 2) aggregate = Math.min(aggregate, windowResult.aggregate);
            else aggregate = Math.max(aggregate, windowResult.aggregate);
            remaining -= take;
            windowNo++;
            inWindowIndex = 0;
        }

        return operation == 1 ? aggregate / length : aggregate;
    }

    // 内部实现：只需要 recordIndex，其它参数（EXPONENT_BITS/ULP_BITS）从本对象的元数据字段读取
    // recordIndex 为 0-based
    private BigDecimal getValueByRecordIndexInternal(long recordIndex) throws IOException {
        long startTime = System.nanoTime();

        // ---- 0) bounds check: total records from on-disk numFenwick ----
        long totalRecords = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNum.length);
        if (recordIndex < 0 || recordIndex >= totalRecords) {
            throw new IllegalArgumentException("recordIndex out of range: " + recordIndex +
                    " (0.." + (totalRecords - 1) + ")");
        }

        // ---- 1) locate windowNo & inWindowIndex ----
        long target = recordIndex + 1; // 1-based for Fenwick lowerBound
        int idx1 = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target); // 1..n
        int windowNo = idx1 - 1; // 0-based

        long recordsBeforeThisWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (recordIndex - recordsBeforeThisWindow);

        long oldNum = windowNum[windowNo];
        if (inWindowIndex < 0 || inWindowIndex >= oldNum) {
            throw new IOException("Index mapping mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex + ", windowNum=" + oldNum);
        }

        // ---- 2) read + decompress ----
        String oldBits = readWindow01(windowNo);
        List<BigDecimal> values = decompressOneWindow01Stream(
                oldBits,
                exponentBitsFromFile,
                ulpBitsFromFile
        );

        if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
            throw new IOException("Decompressed size mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex +
                    ", values.size=" + values.size() +
                    ", window_num[windowNo]=" + oldNum);
        }

        BigDecimal result = values.get(inWindowIndex);

        long endTime = System.nanoTime();

        return BigDecimal.valueOf((endTime - startTime) / 1_000_000.0);
    }

    // 返回最小 idx(1..n)，使得 prefixSum(idx) >= target
    // numFenwick 是 1-indexed，长度 n+1
    private static int fenwickLowerBound(long[] fenwickTree, long target) {
        int n = fenwickTree.length - 1;
        int idx = 0;

        // 找到最高 2 次幂
        int bitMask = 1;
        while ((bitMask << 1) <= n) bitMask <<= 1;

        long sum = 0;
        for (int step = bitMask; step != 0; step >>= 1) {
            int next = idx + step;
            if (next <= n && sum + fenwickTree[next] < target) {
                sum += fenwickTree[next];
                idx = next;
            }
        }
        return idx + 1;
    }


    /**
     * Measures window lookup, decoding, deletion, and re-encoding.
     * Writes the modified window and updates its indexes.
     * @return elapsed time in milliseconds
     */
    private BigDecimal deleteRecordByIndex(long recordIndex) throws IOException {

        long startTime = System.nanoTime();

        // ---- 0) bounds check: total records from on-disk numFenwick ----
        long totalRecords = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNum.length);
        if (recordIndex < 0 || recordIndex >= totalRecords) {
            throw new IllegalArgumentException("recordIndex out of range: " + recordIndex +
                    " (0.." + (totalRecords - 1) + ")");
        }

        // ---- 1) locate windowNo & inWindowIndex ----
        long target = recordIndex + 1; // 1-based for Fenwick lowerBound
        int idx1 = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target); // 1..n
        int windowNo = idx1 - 1; // 0-based

        long recordsBeforeThisWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (recordIndex - recordsBeforeThisWindow);

        long oldNum = windowNum[windowNo];
        if (inWindowIndex < 0 || inWindowIndex >= oldNum) {
            throw new IOException("Index mapping mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex + ", windowNum=" + oldNum);
        }

        // ---- 2) decode, remove, re-encode ----
        String oldBits = readWindow01(windowNo);
        List<BigDecimal> values = decompressOneWindow01Stream(oldBits, exponentBitsFromFile, ulpBitsFromFile);

        if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
            throw new IOException("Decompressed size mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex +
                    ", values.size=" + values.size() +
                    ", window_num[windowNo]=" + oldNum);
        }

        BigDecimal removed = values.remove(inWindowIndex);

        // If you want to allow empty windows, define an empty-window encoding and remove this check.
        if (values.isEmpty()) {
            throw new IllegalStateException("Window becomes empty after delete. windowNo=" + windowNo);
        }

        String newBits = encodeAsOneWindow01String(values, exponentBitsFromFile, ulpBitsFromFile);
        long newLenBits = newBits.length();
        long newNum = values.size();

        rewriteMainBinReplacingOneWindow(windowNo, newBits);
        updateWindowLenAndNum(windowNo, newLenBits, newNum);

        long endTime = System.nanoTime();

        return BigDecimal.valueOf((endTime - startTime)/1_000_000.0);
    }

    /**
     * Rewrites the main .bin as:
     *   [12-bit header] + window0Bits + window1Bits + ... + window(n-1)Bits
     */
    private void rewriteMainBinReplacingOneWindow(int targetWindowNo, String newBits01) throws IOException {
        File tmp = File.createTempFile("our_sql_bin_rewrite_", ".tmp");
        try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
            BitFileWriter bw = new BitFileWriter(out);

            // 12-bit header: WIN(4) + EXP(4) + ULP(4)
            bw.writeFixedBits(winBitsFromFile & 0x0F, 4);
            bw.writeFixedBits(exponentBitsFromFile & 0x0F, 4);
            bw.writeFixedBits(ulpBitsFromFile & 0x0F, 4);

            for (int w = 0; w < windowLenBits.length; w++) {
                String bits = (w == targetWindowNo) ? newBits01 : readWindow01(w);
                bw.write01String(bits);
            }

            bw.flushToByteBoundary();
            long outLen = out.length();

            rafBin.setLength(outLen);

            try (FileChannel inCh = new FileInputStream(tmp).getChannel()) {
                FileChannel outCh = rafBin.getChannel(); // do NOT close
                outCh.position(0);
                long pos = 0;
                while (pos < outLen) {
                    long n = outCh.transferFrom(inCh, pos, outLen - pos);
                    if (n <= 0) break;
                    pos += n;
                }
                outCh.force(true);
            }
        } finally {
            try { Files.deleteIfExists(tmp.toPath()); } catch (IOException ignore) {}
        }
    }

    private static final class BitFileWriter {
        private final RandomAccessFile out;
        private int curByte = 0;
        private int bitPos = 0; // 0..7

        BitFileWriter(RandomAccessFile out) {
            this.out = out;
        }

        void writeBit(int bit) throws IOException {
            curByte = (curByte << 1) | (bit & 1);
            bitPos++;
            if (bitPos == 8) {
                out.write(curByte & 0xFF);
                curByte = 0;
                bitPos = 0;
            }
        }

        void write01String(String bits01) throws IOException {
            for (int i = 0; i < bits01.length(); i++) {
                char c = bits01.charAt(i);
                if (c == '0') writeBit(0);
                else if (c == '1') writeBit(1);
                else throw new IllegalArgumentException("bits01 must contain only '0'/'1'");
            }
        }

        void writeFixedBits(int value, int bits) throws IOException {
            for (int i = bits - 1; i >= 0; i--) {
                writeBit((value >>> i) & 1);
            }
        }

        void flushToByteBoundary() throws IOException {
            if (bitPos == 0) return;
            while (bitPos != 0) writeBit(0);
        }
    }

    /**
     * Measures window lookup, decoding, replacement, and re-encoding.
     * Writes the modified window and updates its indexes.
     * @return elapsed time in milliseconds
     */
    private BigDecimal updateRecordByIndex(long recordIndex, BigDecimal newValue) throws IOException {
        long startTime = System.nanoTime();

        if (newValue == null) throw new IllegalArgumentException("newValue is null");

        // ---- 0) bounds check: total records from on-disk numFenwick ----
        long totalRecords = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNum.length);
        if (recordIndex < 0 || recordIndex >= totalRecords) {
            throw new IllegalArgumentException("recordIndex out of range: " + recordIndex +
                    " (0.." + (totalRecords - 1) + ")");
        }

        // ---- 1) locate windowNo & inWindowIndex ----
        long target = recordIndex + 1; // 1-based for Fenwick lowerBound
        int idx1 = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target); // 1..n
        int windowNo = idx1 - 1; // 0-based

        long recordsBeforeThisWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
        int inWindowIndex = (int) (recordIndex - recordsBeforeThisWindow);

        long oldNum = windowNum[windowNo];
        if (inWindowIndex < 0 || inWindowIndex >= oldNum) {
            throw new IOException("Index mapping mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex + ", windowNum=" + oldNum);
        }

        // ---- 2) decode, replace, re-encode ----
        String oldBits = readWindow01(windowNo);
        List<BigDecimal> values = decompressOneWindow01Stream(oldBits, exponentBitsFromFile, ulpBitsFromFile);

        if (inWindowIndex < 0 || inWindowIndex >= values.size()) {
            throw new IOException("Decompressed size mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex +
                    ", values.size=" + values.size() +
                    ", window_num[windowNo]=" + oldNum);
        }

        BigDecimal oldValue = values.set(inWindowIndex, newValue);

        String newBits = encodeAsOneWindow01String(values, exponentBitsFromFile, ulpBitsFromFile);
        long newLenBits = newBits.length();
        long newNum = values.size(); // should equal oldNum

        rewriteMainBinReplacingOneWindow(windowNo, newBits);
        updateWindowLenAndNum(windowNo, newLenBits, newNum);

        long endTime = System.nanoTime();

        return BigDecimal.valueOf((endTime - startTime)/1_000_000.0);
    }

    /**
     * Measures window lookup, decoding, insertion, and re-encoding.
     * Writes the modified window and updates its indexes.
     * @return elapsed time in milliseconds
     */
    private BigDecimal insertRecordByIndex(long recordIndex, BigDecimal value) throws IOException {

        if (value == null) throw new IllegalArgumentException("value is null");

        long startTime = System.nanoTime();

        long totalRecords = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNum.length);
        if (recordIndex < 0 || recordIndex > totalRecords) {
            throw new IllegalArgumentException("recordIndex out of range: " + recordIndex +
                    " (0.." + totalRecords + ")");
        }

        final int windowNo;
        final int inWindowIndex;

        // ====== 插入逻辑 #1：插入到中间（0 <= recordIndex < totalRecords）======
        if (recordIndex < totalRecords) {
            long target = recordIndex + 1; // 1-based
            int idx1 = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, target); // 1..n
            windowNo = idx1 - 1;

            long recordsBeforeThisWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
            inWindowIndex = (int) (recordIndex - recordsBeforeThisWindow);
        }
        // ====== 插入逻辑 #2：追加到末尾（recordIndex == totalRecords）======
        else {
            if (totalRecords == 0) {
                // 全库为空：塞进第0个窗口的第0个位置
                windowNo = 0;
                inWindowIndex = 0;
            } else {
                // 找到“最后一条记录”所在窗口（也就是最后一个非空窗口）
                int idx1 = fenwickLowerBoundFromFile(rafNumFen, numFenMeta, totalRecords); // target=totalRecords
                windowNo = idx1 - 1;

                long recordsBeforeThisWindow = fenwickPrefixSumFromFile(rafNumFen, numFenMeta, windowNo);
                inWindowIndex = (int) (recordIndex - recordsBeforeThisWindow); // = oldNum (append)
            }
        }

        checkWindowNo(windowNo);

        // ---- 2) decode, insert, re-encode ----
        String oldBits = readWindow01(windowNo);
        List<BigDecimal> values = decompressOneWindow01Stream(oldBits, exponentBitsFromFile, ulpBitsFromFile);

        if (inWindowIndex < 0 || inWindowIndex > values.size()) {
            throw new IOException("Insert index mismatch: windowNo=" + windowNo +
                    ", inWindowIndex=" + inWindowIndex +
                    ", values.size=" + values.size());
        }

        values.add(inWindowIndex, value);

        String newBits = encodeAsOneWindow01String(values, exponentBitsFromFile, ulpBitsFromFile);
        long newLenBits = newBits.length();
        long newNum = values.size();

        rewriteMainBinReplacingOneWindow(windowNo, newBits);
        updateWindowLenAndNum(windowNo, newLenBits, newNum);

        long endTime = System.nanoTime();
        return BigDecimal.valueOf((endTime - startTime)/1_000_000.0);
    }

}
