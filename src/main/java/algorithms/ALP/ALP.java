package algorithms.ALP;

import algorithms.ALP.decoder.DoubleALPDecoder;
import algorithms.ALP.encoder.DoubleALPEncoder;
import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import enums.DataTypeEnums;

public class ALP extends Algorithm {
    public ALP(){
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleALPEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleALPDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
