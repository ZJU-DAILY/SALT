package algorithms.ElfPlus;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.ElfPlus.decoder.DoubleElfPlusDecoder;
import algorithms.ElfPlus.encoder.DoubleElfPlusEncoder;
import algorithms.Encoder;
import enums.DataTypeEnums;

public class ElfPlus extends Algorithm {
    public ElfPlus() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfPlusEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfPlusDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
