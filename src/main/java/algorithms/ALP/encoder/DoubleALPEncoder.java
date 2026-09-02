package algorithms.ALP.encoder;

import algorithms.ALP.ALPTools;
import algorithms.Encoder;
import enums.DataTypeEnums;
import utils.BinaryTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DoubleALPEncoder extends Encoder {
    protected int size = DataTypeEnums.DOUBLE.getSize();
    protected double[][] group = new double[10][1024];
    protected int r = 0;
    protected int c = 0;

    protected int n = 5;
    protected int m = 256;
    protected int k = 8;
    protected int w = 512;

    public DoubleALPEncoder(String outputPath) {
        super(outputPath);
    }

    protected ALPTools.Pair[] first_sampling() {

        int a = 10 / n;
        int b = 1024 / m;


        Map<ALPTools.Pair, Integer> pair_map = new HashMap<>();

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                for (int e = 0; e < 23; e++) {
                    for (int f = 0; f <= e; f++) {
                        double v = group[i * a][j * b];
                        long enc_v = Math.round(v * ALPTools.getP10(e) * ALPTools.getP10(-f));
                        double dec_v = enc_v * ALPTools.getP10(-e) * ALPTools.getP10(f);
                        if (Double.doubleToRawLongBits(dec_v) == Double.doubleToRawLongBits(v)) {
                            ALPTools.Pair pir = new ALPTools.Pair(e, f);

                            int times = pair_map.getOrDefault(pir, 0);
                            pair_map.put(pir, times + 1);
                        }
                    }
                }
            }
        }

        ALPTools.Pair[] combinations = new ALPTools.Pair[k]; //
        List<Map.Entry<ALPTools.Pair, Integer>> list = new ArrayList<>(pair_map.entrySet());

        // 根据值对列表进行降序排序
        list.sort((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()));

        int id = 0;
        for (Map.Entry<ALPTools.Pair, Integer> entry : list) {
            if (id >= k) break;
            combinations[id++] = entry.getKey();
        }

        return combinations;
    }

    protected ALPTools.Pair second_sampling(double[] row, ALPTools.Pair[] combinations) {
        int c = 1024 / w;

        ALPTools.Pair best = new ALPTools.Pair(0, 0);
        int best_exc_times = 1024;
        int flag = 0;

        for (int i = 0; i < k; i++) {
            ALPTools.Pair pir = combinations[i];
            int e = pir.getE();
            int f = pir.getF();
            int exc_times = 0;

            for (int j = 0; j < w; j++) {
                double v = row[j * c];
                long enc_v = Math.round(v * ALPTools.getP10(e) * ALPTools.getP10(-f));
                double dec_v = enc_v * ALPTools.getP10(-e) * ALPTools.getP10(f);
                if (Double.doubleToRawLongBits(dec_v) != Double.doubleToRawLongBits(v)) {
                    exc_times++;
                }
            }

            if (exc_times < best_exc_times) {
                best = pir;
                best_exc_times = exc_times;
                flag = 0;
            } else {
                flag++;
                if (flag >= 2) break;
            }
        }


        return best;
    }


    protected void FFOR(long[] enc_vec) {
        long minv = enc_vec[0];

        for (int i = 1; i < 1024; i++) {
            minv = Math.min(enc_vec[i], minv);
        }

        long max_v = Long.MIN_VALUE;
        for (int i = 0; i < 1024; i++) {
            max_v = Math.max(enc_vec[i], max_v);
            enc_vec[i] -= minv;
        }

        int cost = 64 - BinaryTools.leadZeros(max_v - minv, size);

        out.write(cost, 8);
        out.write(minv, 64);

        for (int i = 0; i < 1024; i++) {
            out.write(enc_vec[i], cost);
        }
    }

    protected void reset() {
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 1024; j++) {
                group[i][j] = 0;
            }
        }
    }

    protected void ALP() {
        ALPTools.Pair[] combinations = first_sampling();

        for (int i = 0; i < 10; i++) {
            ALPTools.Pair best = second_sampling(group[i], combinations);
            int e = best.getE();
            int f = best.getF();

            out.write(e, 5);
            out.write(f, 5);

            long[] enc_vec = new long[1024];

            int exc_num = 0;
            int[] exc_id = new int[1024];
            double[] exc_vec = new double[1024];

            boolean first = false;
            long first_suc = 0;

            for (int j = 0; j < 1024; j++) {
                double v = group[i][j];
                long enc_v = Math.round(v * ALPTools.getP10(e) * ALPTools.getP10(-f));
                double dec_v = enc_v * ALPTools.getP10(-e) * ALPTools.getP10(f);
                if (Double.doubleToRawLongBits(dec_v) == Double.doubleToRawLongBits(v)) {
                    if (!first) {
                        first = true;
                        first_suc = enc_v;
                    }
                    enc_vec[j] = enc_v;
                } else {
                    exc_id[exc_num] = j;
                    exc_vec[exc_num++] = v;
                }
            }

            out.write(exc_num, 11);

            for (int j = 0; j < exc_num; j++) {
                out.write(exc_id[j], 10);
                out.write(exc_vec[j], 64);
                enc_vec[exc_id[j]] = first_suc;
            }

            FFOR(enc_vec);
        }

        reset();
    }

    @Override
    public int close() {
        ALP();
        return out.track_bits();
    }

    @Override
    public int encode(double value) {
        group[r][c] = value;
        c++;
        if (c == 1024) {
            r++;
            if (r == 10) {
                ALP();
                r = 0;
            }
            c = 0;
        }
        return out.track_bits();
    }
}
