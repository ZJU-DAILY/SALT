package compression.kangaroo;

import java.io.ByteArrayOutputStream;

final class BitStream {
    static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int current, used;
        long bits;

        void write(long value, int width) {
            if (width < 0 || width > 64) throw new IllegalArgumentException("Invalid bit width: " + width);
            bits += width;
            while (width > 0) {
                int n = Math.min(8 - used, width);
                current = (current << n) | (int) ((value >>> (width - n)) & ((1 << n) - 1));
                used += n;
                width -= n;
                if (used == 8) { bytes.write(current); current = 0; used = 0; }
            }
        }

        byte[] finish() {
            if (used != 0) { bytes.write(current << (8 - used)); used = 0; current = 0; }
            return bytes.toByteArray();
        }
    }

    static final class Reader {
        private final byte[] bytes;
        private final int offset;
        final long limit;
        long position;

        Reader(byte[] bytes, int offset, long limit) { this.bytes = bytes; this.offset = offset; this.limit = limit; }

        long read(int width) {
            if (width < 0 || width > 64) throw new IllegalArgumentException("Invalid bit width: " + width);
            if (width > limit - position) throw new IllegalArgumentException("Truncated Kangaroo payload at bit " + position);
            long result = 0;
            while (width > 0) {
                int shift = (int) (position & 7);
                int n = Math.min(8 - shift, width);
                result = (result << n) | ((bytes[offset + (int) (position >>> 3)] >>> (8 - shift - n)) & ((1 << n) - 1));
                width -= n;
                position += n;
            }
            return result;
        }
    }

    static long lowMask(int width) { return width == 64 ? -1L : (1L << width) - 1; }
    private BitStream() { }
}
