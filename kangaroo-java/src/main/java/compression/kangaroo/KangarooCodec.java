package compression.kangaroo;

/** Convenience API; use encoder/decoder directly to process values incrementally. */
public final class KangarooCodec {
    public static byte[] compress(double[] values, Options options) {
        KangarooEncoder encoder=new KangarooEncoder(options);
        for(double value:values) encoder.add(value);
        return encoder.finish();
    }
    public static double[] decompress(byte[] block) {
        KangarooDecoder decoder=new KangarooDecoder(block);
        double[] values=new double[decoder.size()];
        for(int i=0;i<values.length;i++) values[i]=decoder.readDouble();
        return values;
    }
    private KangarooCodec() { }
}
