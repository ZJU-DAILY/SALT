package algorithms.Kangaroo;

import compression.kangaroo.Options;

public class DoubleKangarooCompactEncoder extends DoubleKangarooEncoder {
    public DoubleKangarooCompactEncoder(String path) { super(path,"",Options.Search.COMPACT); }
    public DoubleKangarooCompactEncoder(String path,String config) { super(path,config,Options.Search.COMPACT); }
}
