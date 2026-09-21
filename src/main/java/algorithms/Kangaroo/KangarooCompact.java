package algorithms.Kangaroo;

import algorithms.Algorithm;
import enums.DataTypeEnums;

public class KangarooCompact extends Algorithm {
    public KangarooCompact() {
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleKangarooCompactEncoder.class);
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleKangarooDecoder.class);
    }
}
