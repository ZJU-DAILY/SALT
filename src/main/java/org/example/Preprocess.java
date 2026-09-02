package org.example;

import Experiment.TestBuilder;
import enums.AlgorithmEnums;
import enums.DataTypeEnums;

import java.util.HashSet;
import java.util.Set;

public class Preprocess {


    public static void main(String[] args) {
        String data_path = "./datasets/preprocess";
        String store_path = "./storage";
        String result_path = "./results/preprocess";
        AlgorithmEnums[] methods = new AlgorithmEnums[]{
                AlgorithmEnums.ALP,
                AlgorithmEnums.DeXOR,
                AlgorithmEnums.GORILLA,
                AlgorithmEnums.ElfPlus,
                AlgorithmEnums.CHIMP,
//                AlgorithmEnums.CHIMP128,
                AlgorithmEnums.Elf,
                AlgorithmEnums.Camel,
                AlgorithmEnums.SALTE
        };
//        String config_path = "./config.txt";
        String config_path = "";
//        AlgorithmEnums[] methods = AlgorithmEnums.values();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-in":
                    data_path = args[++i];
                    break;
                case "-out":
                    store_path = args[++i];
                    break;
                case "-log":
                    result_path = args[++i];
                    break;
                case "-config":
                    config_path = args[++i];
                    break;
                case "-m":
                    Set<AlgorithmEnums> set = new HashSet<>();
                    while(++i < args.length){
                        String name = args[i];
                        AlgorithmEnums alg = AlgorithmEnums.CheckName(name);
                        if(alg==null){
                            --i;
                            break;
                        }
                        set.add(alg);
                    }
                    if(!set.isEmpty())methods = set.toArray(new AlgorithmEnums[0]);
                    break;
            }
        }

        TestBuilder t1 = new TestBuilder(DataTypeEnums.DOUBLE, data_path, store_path, result_path,config_path, methods);
        t1.test_comp();
        t1.test_decomp();
        t1.write_results();

    }
}
