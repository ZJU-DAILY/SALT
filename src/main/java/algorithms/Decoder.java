package algorithms;

import utils.StreamReader;

import java.util.HashMap;
import java.util.Map;

/**
 *  压缩/编码的公共抽象父类
 *  The common abstract superclass for compression.
 * */

public abstract class Decoder {
    protected StreamReader in;
    protected Map<String, String> config = new HashMap<>();

    public Decoder(String inputPath) {
        this.in = new StreamReader(inputPath);
    }

    public Decoder(String inputPath,String config) {
        this.in = new StreamReader(inputPath);
        this.config = this.parseStringToMap(config);
    }

    public int decodeInt() {
        return 0;
    }

    public long decodeLong() {
        return 0;
    }

    public float decodeFloat() {
        return 0;
    }

    public double decodeDouble() {
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
}
