package algorithms.ElfStar;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.ElfStar.decoder.DoubleElfStarDecoder;
import algorithms.ElfStar.encoder.DoubleElfStarEncoder;
import algorithms.Encoder;
import enums.DataTypeEnums;


public class ElfStar extends Algorithm {
    public ElfStar() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfStarEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleElfStarDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
