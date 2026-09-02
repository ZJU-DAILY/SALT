package algorithms;

import utils.StreamWriter;

import java.util.HashMap;
import java.util.Map;

/**
 *  解压缩/解码的公共抽象父类
 *  The common abstract superclass for compression.
 * */

public abstract class Encoder {

    protected StreamWriter out;
    protected Map<String, Double> meta = new HashMap<>();
    protected Map<String, String> config = new HashMap<>();

    public Encoder(String outputPath) {
        this.out = new StreamWriter(outputPath);
    }

    public Encoder(String outputPath,String config) {
        this.out = new StreamWriter(outputPath);
        this.config = this.parseStringToMap(config);
    }

    public void flush() {
        this.out.clear();
    }

    public int encode(int value) {
        return 0;
    }

    public int encode(long value) {
        return 0;
    }

    public int encode(float value) {
        return 0;
    }

    public int encode(double value) {
        return 0;
    }

    public int close(){
        return 0;
    }

    public Map<String, String> parseStringToMap(String input) {
        Map<String, String> map = new HashMap<>();
        if (input.startsWith("{") && input.endsWith("}")) {
            input = input.substring(1, input.length() - 1);
        }
        String[] pairs = input.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                map.put(key, value);
            }
        }
        return map;
    }


    public Map<String, Double> getMeta() {
        return meta;
    }
}
