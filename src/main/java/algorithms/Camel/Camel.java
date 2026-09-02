package algorithms.Camel;

import algorithms.Algorithm;
import algorithms.Camel.decoder.DoubleCamelDecoder;
import algorithms.Camel.encoder.DoubleCamelEncoder;
import algorithms.Decoder;
import algorithms.Encoder;
import enums.DataTypeEnums;

public class Camel extends Algorithm {
    public Camel() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleCamelEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleCamelDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
