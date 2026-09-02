package algorithms.Gorilla;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.Gorilla.decoder.DoubleGorillaDecoder;
import algorithms.Gorilla.encoder.DoubleGorillaEncoder;
import enums.DataTypeEnums;

public class Gorilla extends Algorithm {
    public Gorilla(){
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleGorillaEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleGorillaDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
