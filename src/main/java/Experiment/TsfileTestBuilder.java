package Experiment;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.read.TsFileReader;
import org.apache.tsfile.read.TsFileSequenceReader;
import org.apache.tsfile.read.common.Path;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.read.expression.IExpression;
import org.apache.tsfile.read.expression.QueryExpression;
import org.apache.tsfile.read.expression.impl.BinaryExpression;
import org.apache.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.tsfile.read.filter.factory.TimeFilterApi;
import org.apache.tsfile.read.query.dataset.QueryDataSet;
import org.apache.tsfile.write.TsFileWriter;
import org.apache.tsfile.write.record.TSRecord;
import org.apache.tsfile.write.record.datapoint.DataPoint;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.apache.tsfile.write.schema.MeasurementSchema;
import utils.TableStreamer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TsfileTestBuilder {

    private String store_path = "test.tsfile";
    private TSEncoding encoding_type = TSEncoding.PLAIN;
    private CompressionType compression_type = CompressionType.UNCOMPRESSED;

    public static void test_vector_tsfile() throws Exception {

        String store_path = "d1.tsfile";

        // 固定编码与压缩方式
        TSEncoding[] encodings = new TSEncoding[]{

                TSEncoding.GORILLA,
                TSEncoding.DEXOR,
        };
        CompressionType compression_type = CompressionType.UNCOMPRESSED;   // 固定

        // 原有路径 + 新增的 iris.data
        String[] paths = new String[]{
                "./datasets/vector/siftsmall_base.csv",
                "./datasets/vector/winequality-red.csv",
                "./datasets/vector/winequality-white.csv",
        };

        Map<String, Map<String, Map<String, Double>>> table = new LinkedHashMap<>();
        table.putIfAbsent(compression_type.name(), new LinkedHashMap<>());
        for (TSEncoding enc : encodings) {
            table.get(compression_type.name()).putIfAbsent(enc.name(), new LinkedHashMap<>());
        }

        for (TSEncoding encoding : encodings) {
            for (String input_path : paths) {
                TsfileTestBuilder tsfileTestBuilder =
                        new TsfileTestBuilder(store_path, encoding, compression_type);

                long start_time = System.nanoTime();
                long total = tsfileTestBuilder.write_csv(input_path);   // .data
                long end_time = System.nanoTime();
                double compTimeSec = (end_time - start_time) / 1_000_000_000.0;

                long bytes = Files.size(Paths.get(store_path));
                double CR = bytes * 8.0 / total;
                double compSpeed = (total * 8.0) / 1024 / 1024 / compTimeSec; // MB/s

                start_time = System.nanoTime();
                tsfileTestBuilder.query_all();
                end_time = System.nanoTime();
                double queryTimeMs = (end_time - start_time) / 1_000_000.0;
                double queryLatency = queryTimeMs / total * 1000;   // ms per 1k

                Map<String, Double> metrics =
                        table.get(compression_type.name()).get(encoding.name());
                metrics.put(input_path + "_CR", CR);
                metrics.put(input_path + "_Comp_speed", compSpeed);
                metrics.put(input_path + "_query_latency", queryLatency);
            }
        }

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(Paths.get("result_vector.csv")))) {

            // 1. 表头：一级列 + 二级列
            StringBuilder header = new StringBuilder("compression_type,encoding");
            for (String metric : new String[]{"CR", "Comp_speed", "query_latency"}) {
                for (String path : paths) {
                    header.append(',').append(metric).append('_').append(path);
                }
            }
            pw.println(header.toString());


            CompressionType ct = compression_type;
            String ctName = ct.name();
            for (TSEncoding enc : encodings) {
                String encName = enc.name();
                Map<String, Double> row = table.get(ctName).get(encName);

                StringBuilder sb = new StringBuilder(ctName).append(',').append(encName);
                for (String metric : new String[]{"CR", "Comp_speed", "query_latency"}) {
                    for (String path : paths) {
                        sb.append(',').append(row.getOrDefault(path + "_" + metric, 0.0));
                    }
                }
                pw.println(sb.toString());
            }

        }
        System.out.println("result_vector.csv");
    }

    public static void test_tsfile() throws Exception {

        String store_path = "d1.tsfile";

        TSEncoding[] encodings = new TSEncoding[]{TSEncoding.DEXOR,
                TSEncoding.GORILLA};
        CompressionType[] compressions = new CompressionType[]{
                CompressionType.UNCOMPRESSED, CompressionType.LZ4, CompressionType.SNAPPY};
        String[] paths = new String[]{
                "./datasets/Overall/City-temp.csv",
                "./datasets/Overall/Food-price.csv",
                "./datasets/Overall/POI-lat.csv"};

        Map<String, Map<String, Map<String, Double>>> table = new LinkedHashMap<>();

        for (CompressionType ct : compressions) {
            table.putIfAbsent(ct.name(), new LinkedHashMap<>());
            for (TSEncoding enc : encodings) {
                table.get(ct.name()).putIfAbsent(enc.name(), new LinkedHashMap<>());
            }
        }

        for (TSEncoding encoding : encodings) {
            for (CompressionType compression_type : compressions) {
                for (String input_path : paths) {
                    TsfileTestBuilder tsfileTestBuilder = new TsfileTestBuilder(store_path, encoding, compression_type);

                    long start_time = System.nanoTime();
                    long total = tsfileTestBuilder.write_csv_stream(input_path, 1); // data_length
                    long end_time = System.nanoTime();
                    double compTimeSec = (end_time - start_time) / 1_000_000_000.0;

                    java.nio.file.Path path = Paths.get(store_path);
                    long bytes = Files.size(path);
                    double CR = bytes * 8.0 / total;

                    double compSpeed = (total * 8.0) / 1024 / 1024 / compTimeSec; // MB/s

                    start_time = System.nanoTime();
                    tsfileTestBuilder.query_all();
                    end_time = System.nanoTime();
                    double queryTimeMs = (end_time - start_time) / 1_000_000.0;
                    double queryLatency = queryTimeMs / total * 1000; //

                    String colKey = input_path;
                    String rowKey1 = compression_type.name();
                    String rowKey2 = encoding.name();

                    Map<String, Double> metrics = table.get(rowKey1).get(rowKey2);
                    metrics.put(colKey + "_CR", CR);
                    metrics.put(colKey + "_Comp_speed", compSpeed);
                    metrics.put(colKey + "_query_latency", queryLatency);
                }
            }
        }


        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(Paths.get("result_swapped.csv")))) {

            // 1. 表头：一级列 + 二级列
            StringBuilder header = new StringBuilder("compression_type,encoding");
            for (String metric : new String[]{"CR", "Comp_speed", "query_latency"}) {
                for (String path : paths) {
                    header.append(',').append(metric).append('_').append(path);
                }
            }
            pw.println(header.toString());


            for (CompressionType ct : compressions) {
                String ctName = ct.name();
                for (TSEncoding enc : encodings) {
                    String encName = enc.name();
                    Map<String, Double> row = table.get(ctName).get(encName);

                    StringBuilder sb = new StringBuilder(ctName).append(',').append(encName);
                    for (String metric : new String[]{"CR", "Comp_speed", "query_latency"}) {
                        for (String path : paths) {
                            sb.append(',').append(row.getOrDefault(path + "_" + metric, 0.0));
                        }
                    }
                    pw.println(sb.toString());
                }
            }
        }
        System.out.println("result_swapped.csv");
    }

    public TsfileTestBuilder(String store_path, TSEncoding encoding_type, CompressionType compression_type) throws IOException {
        this.store_path = store_path;
        if(encoding_type != null)this.encoding_type = encoding_type;
        if(compression_type != null)this.compression_type = compression_type;
    }

    public long write_csv_stream(String input_path,int col_id) throws Exception {
        TableStreamer table = new TableStreamer(input_path);

        long total =0;

        File f = new File(store_path);
        TsFileWriter tsFileWriter = new TsFileWriter(f);
        List<IMeasurementSchema> schema1 = new ArrayList<>();
        schema1.add(new MeasurementSchema("val", TSDataType.DOUBLE, encoding_type,compression_type));
        tsFileWriter.registerTimeseries(new Path("testpanel1"), schema1);

        while (true) {
            try {
                total++;
                TSRecord tsRecord = new TSRecord("testpanel1",total);
                double v = table.getDouble(col_id);
                tsRecord.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE, "val", String.valueOf(v)));
                tsFileWriter.writeRecord(tsRecord);
                table.next();
            } catch (Exception e) {
                break;
            }
        }


        tsFileWriter.close();
        return total;
    }

    public long write_csv(String input_path) throws Exception {
        TableStreamer table = new TableStreamer(input_path);

        long total =0;
        int colCnt = table.column();

        File f = new File(store_path);
        TsFileWriter tsFileWriter = new TsFileWriter(f);
        List<IMeasurementSchema> schemaList = new ArrayList<>();
        for (int c = 0; c < colCnt; c++) {
            try {
                double v = table.getDouble(c);
            } catch (Exception e) {
                continue;
            }
            schemaList.add(new MeasurementSchema("val" + c,
                    TSDataType.DOUBLE,
                    encoding_type,
                    compression_type));
        }
        tsFileWriter.registerTimeseries(new Path("testpanel1"), schemaList);

        while (true) {
            try {
                TSRecord tsRecord = new TSRecord("testpanel1",total);

                for (int c = 0; c < colCnt; c++) {
                    double v = 0;
                    try {
                        v = table.getDouble(c);
                    } catch (Exception e) {
                        continue;
                    }
                    total++;
                    tsRecord.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE,
                            "val" + c,
                            String.valueOf(v)));

                }
                tsFileWriter.writeRecord(tsRecord);
                table.next();
            } catch (Exception e) {
                break;
            }
        }

        tsFileWriter.close();
        return total;
    }


    public void write(double value) throws Exception {
        File f = new File(store_path);
        TsFileWriter tsFileWriter = new TsFileWriter(f);

        List<IMeasurementSchema> schema1 = new ArrayList<>();
        schema1.add(new MeasurementSchema("val", TSDataType.DOUBLE, encoding_type, compression_type));
        tsFileWriter.registerTimeseries(new Path("testpanel1"), schema1);

        TSRecord tsRecord = new TSRecord("testpanel1",1);
        tsRecord.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE, "val", String.valueOf(value)));
        tsFileWriter.writeRecord(tsRecord);

        tsFileWriter.close();
    }

    public void query_all() throws Exception {
        TsFileSequenceReader reader = new TsFileSequenceReader(store_path);
        TsFileReader tsFileReader = new TsFileReader(reader);

        ArrayList<Path> paths = new ArrayList<>();
        paths.add(new Path("testpanel1","val",false));

        QueryExpression queryExpression = QueryExpression.create(paths, null);

        QueryDataSet queryDataSet = tsFileReader.query(queryExpression);
        while (queryDataSet.hasNext()) {
            queryDataSet.next();
        }

        tsFileReader.close();
    }
}
