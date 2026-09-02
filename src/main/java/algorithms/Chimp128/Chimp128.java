package algorithms.Chimp128;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.Chimp128.decoder.DoubleChimp128Decoder;
import algorithms.Chimp128.encoder.DoubleChimp128Encoder;
import enums.DataTypeEnums;

public class Chimp128 extends Algorithm {
    public Chimp128() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleChimp128Encoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleChimp128Decoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
