package algorithms.SALTE;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.SALTE.decoder.DoubleOurEDecoder;
import algorithms.SALTE.encoder.DoubleOurEEncoder;
import enums.DataTypeEnums;

public class SALTE extends Algorithm {
    public SALTE() {
        // Encoder
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleOurEEncoder.class);
        // Decoder
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleOurEDecoder.class);
    }

    @Override
    protected Encoder getEncoder(String data_type, String output_path) throws Exception {
        return super.getEncoder(data_type, output_path);
    }

    @Override
    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        return super.getDecoder(data_type, input_path);
    }
}
