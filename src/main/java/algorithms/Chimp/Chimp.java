package algorithms.Chimp;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.Chimp.decoder.DoubleChimpDecoder;
import algorithms.Chimp.encoder.DoubleChimpEncoder;
import enums.DataTypeEnums;

public class Chimp extends Algorithm {
    public Chimp() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleChimpEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleChimpDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
