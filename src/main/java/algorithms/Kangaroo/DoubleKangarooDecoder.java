package algorithms.Kangaroo;

import algorithms.Decoder;
import compression.kangaroo.KangarooDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Reads KGRJ v1 bytes through the same StreamReader used by DeXOR/SALT. */
public class DoubleKangarooDecoder extends Decoder {
    private final String path;
    private KangarooDecoder delegate;
    public DoubleKangarooDecoder(String path) { super(path);this.path=path; }
    public DoubleKangarooDecoder(String path,String config) { this(path); }
    private void initialize() {
        if(delegate!=null)return;
        try {
            long length=Files.size(Paths.get(path));
            if(length<20 || length>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid KGRJ block size");
            byte[] block=new byte[(int)length];
            for(int i=0;i<block.length;i++)block[i]=(byte)in.readInt(8);
            delegate=new KangarooDecoder(block);
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    @Override public double decodeDouble() { initialize();return delegate.readDouble(); }
}
