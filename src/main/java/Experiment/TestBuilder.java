package Experiment;

import enums.AlgorithmEnums;
import enums.DataTypeEnums;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class TestBuilder {
    private final DataTypeEnums data_type;
    private final String data_path;
    private final String store_path;
    private final String config_path;

    private final String result_path;

    private final List<String> algorithms;

    private Map<String, List<List<String>>> results;
    private int index = 0;

    public TestBuilder(DataTypeEnums data_type, String data_path, String store_path, String result_path,String config_path, AlgorithmEnums[] test_algorithms) {
        this.data_type = data_type;
        this.data_path = data_path;
        this.store_path = store_path;
        this.result_path = result_path;
        this.config_path = config_path;
        algorithms = new ArrayList<>();
        for (AlgorithmEnums algorithmEnum : test_algorithms) {
            algorithms.add(algorithmEnum.getName());
        }
        this.init_result();
    }

    public void init_result() {
        results = new HashMap<>();
        for (String name : algorithms) {
            List<List<String>> result = new ArrayList<>();
            results.put(name, result);
        }
    }

    private void createPath(String path){
        File directory = new File(path);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void test_comp() {
        createPath(store_path);
        for (String name : algorithms) {
            File folder = new File(store_path + "/" + name);
            folder.mkdir();
        }
        dfs_compress(new File(data_path));
    }

    public void test_decomp() {
        index = 0;
        dfs_decompress(new File(data_path));
    }

    public void write_results() {
        createPath(result_path);
        for (String name : algorithms) {
            to_csv(results.get(name), result_path + "/" + name + ".csv");
        }
    }

    private void dfs_compress(File dir) { // N datasets
        if (!dir.isDirectory()) {
            if (dir.getName().endsWith(".csv"))comp_dataset(dir.getName(), dir.getAbsolutePath());
            return;
        }
        File[] folder = dir.listFiles();

        if (folder != null) {
            for (File file : folder) {
                String name = file.getName();
                if (file.isDirectory()) dfs_compress(file);
                else if (name.endsWith(".csv")) {
                    name = name.substring(0, name.length() - 4);
                    comp_dataset(name, file.getAbsolutePath());
                }
            }
        }
    }

    private void dfs_decompress(File dir) { // N datasets
        if (!dir.isDirectory()) {
            if (dir.getName().endsWith(".csv"))decomp_dataset(dir.getName(), dir.getAbsolutePath());
            return;
        }
        File[] folder = dir.listFiles();

        if (folder != null) {
            for (File file : folder) {
                String name = file.getName();
                if (file.isDirectory()) dfs_decompress(file);
                else if (name.endsWith(".csv")) {
                    name = name.substring(0, name.length() - 4);
                    decomp_dataset(name, file.getAbsolutePath());
                }
            }
        }
    }

    private void init_result_header(List<List<String>> result, Set<String> field) {
        List<String> row = new ArrayList<>();
        row.add("Dataset");
        row.addAll(field);
        result.add(row);
    }

    private void comp_dataset(String dataset_name, String table_path) { // 1 dataset N algorithms
        for (String algorithm_name : algorithms) {
            try {
                String output_path = store_path + "/" + algorithm_name + "/" + dataset_name + "." + algorithm_name.toLowerCase();
                CompBuilder comp = new CompBuilder(data_type, algorithm_name, dataset_name, table_path, output_path, config_path);
                comp.compress();
                Map<String, String> comp_info = comp.getInfo();
                List<List<String>> result = results.get(algorithm_name);
                if (result.isEmpty()) init_result_header(result, comp_info.keySet());
                List<String> row = new ArrayList<>();
                row.add(dataset_name);
                for (String key : comp_info.keySet()) {
                    row.add(comp_info.get(key));
                }
                result.add(row);
            } catch (Exception ignored) {
            }
        }
    }

    private void decomp_dataset(String dataset_name, String table_path) { // 1 dataset N algorithms
        index++;
        for (String algorithm_name : algorithms) {
            try {
                String input_path = store_path + "/" + algorithm_name + "/" + dataset_name + "." + algorithm_name.toLowerCase();
                DecompBuilder decomp = new DecompBuilder(data_type, algorithm_name, dataset_name, table_path, input_path, config_path);
                decomp.test_decompress();
                Map<String, String> decomp_info = decomp.getInfo();
                List<List<String>> result = results.get(algorithm_name);
                if (index == 1) {
                    if (result.isEmpty()) init_result_header(result, decomp_info.keySet());
                    else result.get(0).addAll(decomp_info.keySet());
                }
                List<String> row;
                if (index < result.size()) row = result.get(index);
                else {
                    row = new ArrayList<>();
                    result.add(row);
                }
                for (String key : decomp_info.keySet()) {
                    row.add(decomp_info.get(key));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void to_csv(List<List<String>> data, String path) {
        try (CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(path), CSVFormat.DEFAULT)) {
            for (List<String> row : data) {
                csvPrinter.printRecord(row);
            }
            System.out.println("results save in " + path);
        } catch (IOException e) {
//            e.printStackTrace();
        }
    }
}