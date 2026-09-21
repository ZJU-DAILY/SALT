package algorithms.Kangaroo;

import algorithms.Encoder;
import compression.kangaroo.KangarooEncoder;
import compression.kangaroo.Options;

/** One caller-defined block. Output is deferred to close(), like a batch codec. */
public class DoubleKangarooEncoder extends Encoder {
    private final KangarooEncoder delegate;
    private boolean closed;

    public DoubleKangarooEncoder(String path) { this(path,"",Options.Search.FAST); }
    public DoubleKangarooEncoder(String path,String config) { this(path,config,Options.Search.FAST); }
    protected DoubleKangarooEncoder(String path,String config,Options.Search search) {
        super(path);
        int window=32;
        if(config!=null && !config.trim().isEmpty()) {
            this.config=parseStringToMap(config);
            for(String key:this.config.keySet())
                if(!"window".equals(key))throw new IllegalArgumentException("Unsupported Kangaroo option: "+key);
            if(this.config.containsKey("window"))window=Integer.parseInt(this.config.get("window"));
        }
        delegate=new KangarooEncoder(new Options(window,search,true,true));
    }
    @Override public int encode(double value) {
        if(closed)throw new IllegalStateException("Encoder closed");
        delegate.add(value);
        return 0; // Actual serialized bits, including framing, are returned at close.
    }
    @Override public int close() {
        if(closed)return 0;
        byte[] bytes=delegate.finish();
        int bits=Math.toIntExact(8L*bytes.length);
        for(byte value:bytes)out.write(value&255,8);
        closed=true;
        return bits;
    }
    @Override public void flush() {
        close();
        super.flush();
    }
}
