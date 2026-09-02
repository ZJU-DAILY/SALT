package algorithms.Elf;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Elf.decoder.DoubleElfDecoder;
import algorithms.Elf.encoder.DoubleElfEncoder;
import algorithms.Encoder;
import enums.DataTypeEnums;

public class Elf extends Algorithm {
    public Elf() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
