package algorithms;

import algorithms.ALP.ALP;
import algorithms.DeXOR.DeXOR;
import algorithms.Kangaroo.Kangaroo;
import algorithms.Kangaroo.KangarooCompact;
import algorithms.Camel.Camel;
import algorithms.Chimp.Chimp;
import algorithms.Chimp128.Chimp128;
import algorithms.Elf.Elf;
import algorithms.ElfPlus.ElfPlus;
import algorithms.ElfStar.ElfStar;
import algorithms.Gorilla.Gorilla;
import algorithms.SALTE.SALTE;
import algorithms.SElfStar.SElfStar;
import algorithms.SALTSQL.SALTSQL;
import enums.AlgorithmEnums;

import java.util.HashMap;
import java.util.Map;

/**
 * 用于管理所有压缩算法
 * Designed to oversee all compression algorithms,
 * this class serves as a centralized manager for various compression techniques.
 */

public class AlgorithmsManager {
    private static final Map<String, Class<?>> AlgorithmClassMap = new HashMap<>();

    static {
        AlgorithmClassMap.put(AlgorithmEnums.GORILLA.getName(), Gorilla.class);
        AlgorithmClassMap.put(AlgorithmEnums.CHIMP.getName(), Chimp.class);
        AlgorithmClassMap.put(AlgorithmEnums.CHIMP128.getName(), Chimp128.class);
        AlgorithmClassMap.put(AlgorithmEnums.DeXOR.getName(), DeXOR.class);
        AlgorithmClassMap.put(AlgorithmEnums.Kangaroo.getName(), Kangaroo.class);
        AlgorithmClassMap.put(AlgorithmEnums.KangarooCompact.getName(), KangarooCompact.class);
        AlgorithmClassMap.put(AlgorithmEnums.Elf.getName(), Elf.class);
        AlgorithmClassMap.put(AlgorithmEnums.ElfPlus.getName(), ElfPlus.class);
        AlgorithmClassMap.put(AlgorithmEnums.Camel.getName(), Camel.class);
        AlgorithmClassMap.put(AlgorithmEnums.ALP.getName(), ALP.class);
        AlgorithmClassMap.put(AlgorithmEnums.ElfStar.getName(), ElfStar.class);
        AlgorithmClassMap.put(AlgorithmEnums.SElfStar.getName(), SElfStar.class);
        AlgorithmClassMap.put(AlgorithmEnums.SALTE.getName(), SALTE.class);
        AlgorithmClassMap.put(AlgorithmEnums.SALTSQL.getName(), SALTSQL.class);
    }

    // todo Check_Valid

    public static Algorithm getAlgorithm(String algorithm_name) throws Exception {
        Class<?> clazz = AlgorithmClassMap.get(algorithm_name);
        if (clazz != null) {
            return (Algorithm) clazz.getDeclaredConstructor().newInstance();
        }
        throw new Exception("No Such Algorithm");
    }

    public static Encoder getEncoder(String data_type, String algorithm_name, String output_path) throws Exception {
        Algorithm instance = getAlgorithm(algorithm_name);
        return instance.getEncoder(data_type, output_path);
    }

    public static Decoder getDecoder(String data_type, String algorithm_name, String input_path) throws Exception {
        Algorithm instance = getAlgorithm(algorithm_name);
        return instance.getDecoder(data_type, input_path);
    }

    public static Encoder getEncoder(String data_type, String algorithm_name, String output_path, String config) throws Exception {
        Algorithm instance = getAlgorithm(algorithm_name);
        return instance.getEncoder(data_type, output_path, config);
    }

    public static Decoder getDecoder(String data_type, String algorithm_name, String input_path, String config) throws Exception {
        Algorithm instance = getAlgorithm(algorithm_name);
        return instance.getDecoder(data_type, input_path, config);
    }

}
