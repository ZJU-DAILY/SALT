package Experiment;

import algorithms.AlgorithmsManager;
import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.Serie;
import utils.TableStreamer;

import java.util.HashMap;
import java.util.Map;

public class SeriesCompBuilder {

    private Encoder encoder;
    private Serie serie;

    private String name;

    private DataTypeEnums dataType;
    private String algorithm_name;
    private String outputPath;

    private long total = 0;
    private double bits = 0;

    private boolean use_log = true;

    private double finish_time = 0;
    Map<String, String> info = new HashMap<>();


    public SeriesCompBuilder(DataTypeEnums dataType, String algorithm_name, String outputPath) throws Exception {
        this.dataType = dataType;
        this.algorithm_name = algorithm_name;
        this.outputPath = outputPath;
        this.encoder = AlgorithmsManager.getEncoder(dataType.getType(), algorithm_name, outputPath);
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

        System.out.println(algorithm_name + " compress \"" + name + "\" success? Total " + total
                + " values stored in \"" + outputPath + "\". Finish time is "
                + result_format(finish_time) + "ms and average bits is " + result_format(comp_bits));

        info.put("total", result_format(total));
        info.put("comp_speed", result_format(comp_speed));
        info.put("comp_bits", result_format(comp_bits));

        Map<String, Double> meta = encoder.getMeta();
        for (String key : meta.keySet()) {
            info.put(key, result_format(meta.get(key)));
        }
    }


    //todo add other types
    public void compress() {
        if (dataType.equals(DataTypeEnums.DOUBLE)) {
//            compressDouble();
        }
        print_trace();
    }

//    protected void compressDouble() {
//        while (true) {
//            try {
//                double v = table.getDouble(1);
//                total++;
//
//                // debug
////                if (table_name.equals("Food-price.csv") && total == 506) {
////                    int k = 111;
////                }
//
//                long start_time = System.nanoTime();
//                bits += encoder.encode(v);
//                long end_time = System.nanoTime();
//                finish_time += (double) (end_time - start_time) / 1000000; // convert to ms
//                table.next();
//            } catch (Exception e) {
//                encoder.flush();
//                break;
//            }
//        }
//    }
}
