package SALT;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class CsvComfirmer {

    public static void main(String[] args) {

        Path dir1 = Paths.get("");
        Path dir2 = Paths.get("dataset");

        if (!Files.isDirectory(dir1) || !Files.isDirectory(dir2)) {
            System.out.println("两个参数都必须是文件夹路径！");
            return;
        }

        try {
            compareFolders(dir1, dir2);
        } catch (IOException e) {
            System.err.println("比较过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 比较两个文件夹下同名的 csv 文件
     */
    private static void compareFolders(Path dir1, Path dir2) throws IOException {
        // 收集两个目录下的 csv 文件名
        Set<String> csvFilesDir1 = listCsvFileNames(dir1);
        Set<String> csvFilesDir2 = listCsvFileNames(dir2);

        // 只比较两个文件夹都存在的 csv 文件（同名）
        Set<String> commonFiles = new HashSet<>(csvFilesDir1);
        commonFiles.retainAll(csvFilesDir2);

        if (commonFiles.isEmpty()) {
            System.out.println("两个文件夹中没有同名的 csv 文件需要比较。");
            return;
        }

        System.out.println("找到同名 csv 文件个数: " + commonFiles.size());
        System.out.println("开始比较...\n");

        for (String fileName : commonFiles) {
            Path file1 = dir1.resolve(fileName);
            Path file2 = dir2.resolve(fileName);

            System.out.println("=== 比较文件: " + fileName + " ===");
            compareCsvFiles(file1, file2); // 发现不一致会在内部直接退出程序
            System.out.println();
        }

        // 不同时存在的文件（只是提示，逻辑上等于“跳过”）
        Set<String> onlyInDir1 = new HashSet<>(csvFilesDir1);
        onlyInDir1.removeAll(csvFilesDir2);

        Set<String> onlyInDir2 = new HashSet<>(csvFilesDir2);
        onlyInDir2.removeAll(csvFilesDir1);

        if (!onlyInDir1.isEmpty()) {
            System.out.println("以下 csv 仅存在于目录1（已跳过比较）:");
            for (String name : onlyInDir1) {
                System.out.println("  " + name);
            }
        }

        if (!onlyInDir2.isEmpty()) {
            System.out.println("以下 csv 仅存在于目录2（已跳过比较）:");
            for (String name : onlyInDir2) {
                System.out.println("  " + name);
            }
        }
    }

    /**
     * 列出目录下所有 .csv 文件名
     */
    private static Set<String> listCsvFileNames(Path dir) throws IOException {
        Set<String> result = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    result.add(entry.getFileName().toString());
                }
            }
        }
        return result;
    }

    /**
     * 按行比较两个 csv 文件的内容
     * 一旦发现不一致，打印信息并退出程序
     */
    private static void compareCsvFiles(Path file1, Path file2) {
        boolean allEqual = true;
        int lineNumber = 0;

        try (BufferedReader br1 = Files.newBufferedReader(file1, StandardCharsets.UTF_8);
             BufferedReader br2 = Files.newBufferedReader(file2, StandardCharsets.UTF_8)) {

            String line1;
            String line2;

            while (true) {
                line1 = br1.readLine();
                line2 = br2.readLine();
                lineNumber++;

                if (line1 == null && line2 == null) {
                    // 两个文件同时结束
                    break;
                } else if (line1 == null) {
                    // file1 行数少
                    System.out.println("不一致: 文件 " + file1.getFileName()
                            + " 在第 " + lineNumber + " 行已结束，但 "
                            + file2.getFileName() + " 仍有内容。");
                    allEqual = false;
                    System.exit(1); // ★ 这里新增退出
                } else if (line2 == null) {
                    // file2 行数少
                    System.out.println("不一致: 文件 " + file2.getFileName()
                            + " 在第 " + lineNumber + " 行已结束，但 "
                            + file1.getFileName() + " 仍有内容。");
                    allEqual = false;
                    System.exit(1); // ★ 这里新增退出
                }

                // 比较这一行
                if (!compareCsvLine(line1, line2)) {
                    System.out.println("不一致: 第 " + lineNumber + " 行内容不同。");
                    System.out.println("  " + file1.getFileName() + ": " + line1);
                    System.out.println("  " + file2.getFileName() + ": " + line2);
                    allEqual = false;
                    System.exit(1); // ★ 这里新增退出
                }
            }

            if (allEqual) {
                System.out.println("结果: 两个文件内容完全一致。");
            } else {
                System.out.println("结果: 两个文件存在差异。");
                // 理论上到不了这里，因为发现差异已经 System.exit(1) 了
            }

        } catch (IOException e) {
            System.err.println("读取文件时出错: " + e.getMessage());
        }
    }

    /**
     * 比较一行 csv（按列比较）
     * 数值字段：末尾 0 多 / 少不影响（1、1.0、1.00 视为相等）
     * 非数值字段：按字符串比较（可根据需求改为忽略前后空格等）
     */
    private static boolean compareCsvLine(String line1, String line2) {
        String[] cols1 = line1.split(",", -1); // -1 保留空列
        String[] cols2 = line2.split(",", -1);

        if (cols1.length != cols2.length) {
            return false;
        }

        for (int i = 0; i < cols1.length; i++) {
            String v1 = cols1[i];
            String v2 = cols2[i];

            if (!equalsWithZeroTolerance(v1, v2)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对单个字段的比较：
     * - 如果两个值都是合法数字，用 BigDecimal 比较数值（自动忽略小数部分尾部 0）
     * - 否则按字符串比较（这里用 trim 后再比较，如果你需要严格区分空格，可以去掉 trim）
     */
    private static boolean equalsWithZeroTolerance(String s1, String s2) {
        if (s1 == null && s2 == null) {
            return true;
        }
        if (s1 == null || s2 == null) {
            return false;
        }

        // 去掉 UTF-8 BOM，然后再 trim
        String t1 = removeBom(s1).trim();
        String t2 = removeBom(s2).trim();

        try {
            BigDecimal d1 = new BigDecimal(t1);
            BigDecimal d2 = new BigDecimal(t2);
            return d1.compareTo(d2) == 0;
        } catch (NumberFormatException e) {
            return t1.equals(t2);
        }
    }

    /**
     * 去掉字符串中的 UTF-8 BOM（U+FEFF）
     */
    private static String removeBom(String s) {
        // 有些时候 BOM 只在开头，这里简单处理掉所有 \uFEFF
        return s.replace("\uFEFF", "");
    }

}
