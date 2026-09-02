package algorithms.SElfStar;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.SElfStar.decoder.DoubleSElfStarDecoder;
import algorithms.SElfStar.encoder.DoubleSElfStarEncoder;
import enums.DataTypeEnums;


public class SElfStar extends Algorithm {
    public SElfStar() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleSElfStarEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleSElfStarDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
