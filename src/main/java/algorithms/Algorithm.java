package algorithms;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 *  算法继承的抽象类，用来识别单个类别的压缩算法并管理其能够压缩的数据类型。
 *  The abstract class for algorithm inheritance is designed to recognize category of compression algorithms.
 *  And manage the types of data it can compress.
 * */

public abstract class Algorithm {
    protected static final Map<String, Class<?>> EncoderClassMap = new HashMap<>();
    protected static final Map<String, Class<?>> DecoderClassMap = new HashMap<>();

    public Set<String> getSupportedDataTypes(){
        return EncoderClassMap.keySet();
    }

    protected Encoder getEncoder(String data_type,String output_path) throws Exception {
        Class<?> clazz = EncoderClassMap.get(data_type);
        if (clazz != null) {
            return (Encoder) clazz.getDeclaredConstructor(String.class).newInstance(output_path);
        }
        throw new Exception("No Such Encoder");
    }

    protected Decoder getDecoder(String data_type, String input_path) throws Exception {
        Class<?> clazz = DecoderClassMap.get(data_type);
        if (clazz != null) {
            return (Decoder) clazz.getDeclaredConstructor(String.class).newInstance(input_path);
        }
        throw new Exception("No Such Decoder");
    }

    protected Encoder getEncoder(String data_type,String output_path, String config) throws Exception {
        Class<?> clazz = EncoderClassMap.get(data_type);
        if (clazz != null) {
            return (Encoder) clazz.getDeclaredConstructor(String.class, String.class).newInstance(output_path, config);
        }
        throw new Exception("No Such Encoder");
    }

    protected Decoder getDecoder(String data_type, String input_path, String config) throws Exception {
        Class<?> clazz = DecoderClassMap.get(data_type);
        if (clazz != null) {
            return (Decoder) clazz.getDeclaredConstructor(String.class, String.class).newInstance(input_path, config);
        }
        throw new Exception("No Such Decoder");
    }
}
