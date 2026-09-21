package Experiment;

import algorithms.AlgorithmsManager;
import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.TableStreamer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompBuilder {

    private Encoder encoder;
    private TableStreamer table;

    private DataTypeEnums dataType;
    private String algorithmName;
    private String outputPath;
    private String tablePath;
    private String configPath;
    private String tableName;

    private long total = 0;
    private double bits = 0;

    private boolean use_log = true;

    private double finish_time = 0;
    Map<String, String> info = new HashMap<>();

    // ---- window speed stats (MB/s) ----
    private static final int SPEED_WINDOW = 100; // 每个区间统计 10000 个值（可调）
    private long winCount = 0;
    private double winBytes = 0.0;
    private long winTimeNs = 0;

    private double compSpeedMinMBps = Double.POSITIVE_INFINITY;
    private double compSpeedMaxMBps = 0.0;

    // ---- per-value bits stats ----
    private double compBitsMin = Double.POSITIVE_INFINITY;
    // max bits with cap: only record if <= 100
    private double compBitsMaxCapped = 0.0;

    public CompBuilder(DataTypeEnums dataType, String algorithm_name, String table_name, String table_path, String output_path, String config_path) throws Exception {
        this.dataType = dataType;
        this.algorithmName = algorithm_name;
        this.tablePath = table_path;
        this.tableName = table_name;
        this.outputPath = output_path;
        this.configPath = config_path;
        String config = null;
        if (!config_path.isEmpty()) config = seekConfig();
        if (config_path != null && config == null)
            this.encoder = AlgorithmsManager.getEncoder(dataType.getType(), algorithm_name, outputPath);
        else this.encoder = AlgorithmsManager.getEncoder(dataType.getType(), algorithm_name, outputPath, config);
        this.table = new TableStreamer(tablePath);
    }

    public String seekConfig() {
        Pattern pattern = Pattern.compile(algorithmName + "\\{([^}]*)\\}");
        String line;

        try (BufferedReader reader = new BufferedReader(new FileReader(configPath))) {
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public Map<String, String> getInfo() {
        return info;
    }

    // result log
    public void setLog(boolean use_Log) {
        this.use_log = use_Log;
    }

    protected String result_format(double v) {
        return String.format("%.2f", v);
    }

    protected void print_trace() {
        if (!use_log) return;
        double comp_speed = (double) (total * dataType.getSize() / 8) / finish_time;
        double comp_bits = bits / total;

        System.out.println(algorithmName + " compress \"" + tableName + "\" success? Total " + total
                + " values stored in \"" + outputPath + "\". Finish time is "
                + result_format(finish_time) + "ms and average bits is " + result_format(comp_bits));

        info.put("total", result_format(total));
        info.put("comp_time", result_format(finish_time));
        info.put("comp_bits", result_format(comp_bits));

        // avg speed: 总输入字节 / 总耗时
        double totalBytes = total * (dataType.getSize() / 8.0);
        double avgMBps = 0.0;
        if (finish_time > 0) {
            avgMBps = (totalBytes / (finish_time / 1000.0)) / (1024.0 * 1024.0);
        }
        if (compSpeedMinMBps == Double.POSITIVE_INFINITY) compSpeedMinMBps = 0.0;

        info.put("comp_speed_avg_MBps", result_format(avgMBps));
        info.put("comp_speed_min_MBps", result_format(compSpeedMinMBps));
        info.put("comp_speed_max_MBps", result_format(compSpeedMaxMBps));

        if (compBitsMin == Double.POSITIVE_INFINITY) compBitsMin = 0.0;

        info.put("comp_bits_min", result_format(compBitsMin));
        info.put("comp_bits_max_le_100", result_format(compBitsMaxCapped));

        Map<String, Double> meta = encoder.getMeta();
        for (String key : meta.keySet()) {
            info.put(key, result_format(meta.get(key)));
        }
    }

    //todo add other types
    public void compress() {
        if (dataType.equals(DataTypeEnums.DOUBLE)) {
            compressDouble();
        }
        print_trace();
    }

    protected void compressDouble() {
        System.out.println(tableName);
        while (true) {
            try {

                double v = table.getDouble(1);

                total++;

                // debug
                if (tableName.equals("Air-sensor") && total == 1) {
                    int k = 111;
                }

                long start_time = System.nanoTime();
                double bitsDelta = encoder.encode(v);
                bits += bitsDelta;

                compBitsMin = Math.min(compBitsMin, bitsDelta);

                if (bitsDelta <= 100.0) {
                    compBitsMaxCapped = Math.max(compBitsMaxCapped, bitsDelta);
                }
                long end_time = System.nanoTime();

                long dtNs = end_time - start_time;
                finish_time += (double) dtNs / 1_000_000;
                if (dtNs > 0) {
                    winCount++;
                    winBytes += dataType.getSize() / 8.0;
                    winTimeNs += dtNs;

                    if (winCount >= SPEED_WINDOW) {
                        double sec = winTimeNs / 1e9;
                        if (sec > 0) {
                            double mbps = (winBytes / sec) / (1024.0 * 1024.0);
                            compSpeedMinMBps = Math.min(compSpeedMinMBps, mbps);
                            compSpeedMaxMBps = Math.max(compSpeedMaxMBps, mbps);
                        }
                        winCount = 0;
                        winBytes = 0.0;
                        winTimeNs = 0;
                    }
                }

                table.next();

            } catch (Exception e) {
                // for batch
                long start_time = System.nanoTime();
                int residual = encoder.close();
                long end_time = System.nanoTime();

                if (residual > 0) {
                    bits += residual;
                    finish_time += (double) (end_time - start_time) / 1000000;
                }

                encoder.flush();
                break;
            }
        }
    }

}
