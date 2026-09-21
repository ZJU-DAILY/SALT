package compression.kangaroo;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

/** Incremental block decoder. Corrupt or truncated framing fails explicitly. */
public final class KangarooDecoder {
    private final BitStream.Reader in;
    private final History history;
    private final Options options;
    private final int count;
    private int emitted, repeats;
    private long lastOriginal;

    public KangarooDecoder(byte[] block) {
        if (block.length < KangarooEncoder.HEADER_BYTES) throw new IllegalArgumentException("Truncated header");
        ByteBuffer header=ByteBuffer.wrap(block);
        if (header.getInt()!=KangarooEncoder.MAGIC || header.get()!=1) throw new IllegalArgumentException("Unknown Kangaroo format/version");
        int flags=header.get()&255, wb=header.get()&255, reserved=header.get()&255;
        if ((flags & ~7)!=0 || wb>16 || reserved!=0) throw new IllegalArgumentException("Invalid block options");
        count=header.getInt(); long bits=header.getLong();
        long available=8L*(block.length-KangarooEncoder.HEADER_BYTES);
        if (count<0 || bits<0 || bits>available || available-bits>7)
            throw new IllegalArgumentException("Invalid payload length/count");
        if (count==0 && bits!=0) throw new IllegalArgumentException("Payload in empty block");
        int padding=(int)(available-bits);
        if (padding>0 && (block[block.length-1] & ((1<<padding)-1))!=0)
            throw new IllegalArgumentException("Nonzero padding");
        options=new Options(1<<wb,(flags&1)!=0?Options.Search.FAST:Options.Search.COMPACT,(flags&2)!=0,(flags&4)!=0);
        // The decoder only requires the ring; no Fast search tables are used.
        history=new History(options.window,Options.Search.COMPACT);
        in=new BitStream.Reader(block,KangarooEncoder.HEADER_BYTES,bits);
    }
    public int size() { return count; }
    public boolean hasNext() { return emitted<count; }
    public double readDouble() { return Double.longBitsToDouble(readRaw()); }

    public long readRaw() {
        if (!hasNext()) throw new NoSuchElementException("End of Kangaroo block");
        if (repeats>0) { repeats--; return delivered(lastOriginal); }
        int kind;
        if (in.read(1)==0) kind=Erasure.ERASED;
        else if (in.read(1)==0) kind=Erasure.FLIPPED;
        else if (in.read(1)==0) {
            if (!options.rle || emitted==0) throw new IllegalArgumentException("Invalid repetition marker");
            repeats=readRun();
            if (repeats>count-emitted) throw new IllegalArgumentException("Run exceeds block record count");
            repeats--; return delivered(lastOriginal);
        } else kind=Erasure.UNCHANGED;
        if (!options.erasure && kind!=Erasure.UNCHANGED) throw new IllegalArgumentException("Unexpected erasure marker");
        long stored=readValue();
        lastOriginal=Erasure.decode(stored,kind);
        history.add(stored);
        return delivered(lastOriginal);
    }

    private long delivered(long value) {
        emitted++;
        if (emitted==count && (repeats!=0 || in.position!=in.limit))
            throw new IllegalArgumentException("Trailing payload or record count mismatch");
        return value;
    }

    private int readRun() {
        long value=0;
        for(int i=0;i<5;i++) {
            int b=(int)in.read(8);
            value |= (long)(b&127) << (7*i);
            if((b&128)==0) {
                if(value==0 || value>Integer.MAX_VALUE || (i>0 && (b&127)==0))
                    throw new IllegalArgumentException("Invalid run length");
                return (int)value;
            }
        }
        throw new IllegalArgumentException("Oversized run length");
    }

    private long readValue() {
        if(history.empty()) return in.read(64);
        if(in.read(1)==1) {
            long ref=history.fromSlot((int)in.read(options.windowBits()));
            if(ref==0) throw new IllegalArgumentException("Non-identical reference with 64 trailing zeros");
            return readCenter(ref,Long.numberOfTrailingZeros(ref),false);
        }
        if(in.read(1)==0) return history.fromSlot((int)in.read(options.windowBits()));
        int trailing=(int)in.read(6);
        return readCenter(history.value(history.latest()),trailing,true);
    }

    private long readCenter(long ref,int trailing,boolean fallback) {
        int code=(int)in.read(3),lead=KangarooEncoder.LEADING[code];
        if(fallback && trailing==63 && code==7) return 0;
        int width=64-lead-trailing-1;
        if(width<0) throw new IllegalArgumentException("Invalid center width");
        long prefix=lead==0?0:ref & (-1L << (64-lead));
        return prefix | (in.read(width) << (trailing+1)) | (1L<<trailing);
    }
}
