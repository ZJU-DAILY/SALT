package algorithms.Kangaroo;

import algorithms.Algorithm;
import enums.DataTypeEnums;

/** Independent Kangaroo v0.2, exposed through the same registry as DeXOR. */
public class Kangaroo extends Algorithm {
    public Kangaroo() {
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleKangarooEncoder.class);
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleKangarooDecoder.class);
    }
}
