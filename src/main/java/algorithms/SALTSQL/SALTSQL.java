package algorithms.SALTSQL;

import algorithms.Algorithm;
import algorithms.Decoder;
import algorithms.Encoder;
import algorithms.SALTSQL.decoder.DoubleSALTSQLDecoder;
import algorithms.SALTSQL.encoder.DoubleSALTSQLEncoder;
import enums.DataTypeEnums;

/**
 * SALTSQL algorithm registration.
 *
 * This class keeps the same four-file organization as SALTE, but the algorithm
 * now belongs to algorithms.SALTSQL and uses a simple sequential SALTSQL-style
 * double compressor/decompressor. It does not build Fenwick trees or sidecar
 * index files.
 */
public class SALTSQL extends Algorithm {
    public SALTSQL() {
        EncoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleSALTSQLEncoder.class);
        DecoderClassMap.put(DataTypeEnums.DOUBLE.getType(), DoubleSALTSQLDecoder.class);
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
