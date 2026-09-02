package algorithms.DeXOR;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.DeXOR.decoder.DoubleDeXORDecoder;
import algorithms.DeXOR.encoder.DoubleDeXOREncoder;
import enums.DataTypeEnums;

public class DeXOR extends Algorithm {
    public DeXOR(){
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleDeXOREncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleDeXORDecoder.class);
    }

    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
