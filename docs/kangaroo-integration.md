# Kangaroo 接入共同实验管线

这里“接入 DeXOR 管线”指接入同一个实验框架，不是把 DeXOR 和 Kangaroo 串联压缩。

## 接入位置

- 独立核心包使用中性名称 `compression.kangaroo`，不表示机构或原作者归属。
- `AlgorithmEnums` / `AlgorithmsManager` 注册 `Kangaroo`（默认Fast，W=32）和 `KangarooCompact`（W=32）。
- `algorithms.Kangaroo` 适配器实现共同的 `Encoder.encode(double)/close()/flush()` 和 `Decoder.decodeDouble()` 接口。
- 适配器使用原有 `utils.StreamWriter`、`utils.StreamReader`，与 DeXOR/SALT 共用文件读写实现。
- 根 Maven 工程直接编译 `kangaroo-java` 的同一份源码和测试，避免复制算法产生两个版本。
- 新增 `Experiment.CheckedBenchmark`，所有方法走同一加载、分块、调度、大小统计、计时与验证流程。

独立 Kangaroo 算法及 KGRJ v1 格式保持原样。适配器先在内存生成一个调用方指定大小的块，再通过共享流写出；解码通过共享流读取块，再调用原解码器。涉及的额外缓冲和复制都计入统一计时，不声称不同算法内部实现的内存管理完全一致。

Kangaroo 的输出延迟到 `close`；`encode` 返回0，`close` 返回完整序列化位数。不能用旧框架的逐值最小位数指标评价它；共同入口直接使用实际文件大小。`flush` 会先调用幂等的 `close`，避免忘记提交末尾游程。该旧接口的 `int` 位数返回值将单个序列化块限制在约256MiB以内；大流应使用有限分块。

## 为什么增加共同入口

现有 `CompBuilder/DecompBuilder/TableStreamer` 存在以下实验口径问题：

1. 首条CSV记录无条件作为表头，原始无表头文件会少一个值。
2. 编码速度使用2^20字节、解码速度使用10^6字节，却均标MB/s。
3. 构造、关闭及flush的计时边界不完整；对延迟输出的算法影响尤其明显。
4. 解码校验被注释，异常被捕获并可能作为正常结束。

为保留旧结果的可追溯性，本轮未重写这些旧类。新入口复用同一个算法注册和同一套文件流，统一上述实验行为。SALT/DeXOR 的编解码算法、窗口、码本和旧结果文件均未改动。

## 运行

### IDEA 原有 Main 入口

运行 `src/main/java/org/example/Main.java` 的 `main()`，默认执行 SALTE 和 Kangaroo Fast。`methods` 数组中也提供了注释掉的 `KangarooCompact`，需要时取消注释。仅运行 Fast 可以在程序参数中填写 `-m Kangaroo`，两种模式填写 `-m Kangaroo KangarooCompact`。

工作目录设为 SALT 根目录。默认输入 `datasets/Overall`，Fast 结果为 `results/Overall/Kangaroo.csv`，Compact 结果为 `results/Overall/KangarooCompact.csv`。此入口保留旧 TestBuilder 规则：跳过首行、每文件使用一个编码器、采用旧位数和速度计量。它不是此前每1000值重置的 CheckedBenchmark；其结果不能直接替换此前表格。重复运行会覆盖所选算法的同名结果，可以用 `-out` 和 `-log` 指定独立输出目录。

### 含逐位检查的共同入口

```powershell
mvn package
java -Xms1g -Xmx1g -cp target/salt-1.0-SNAPSHOT-all.jar Experiment.CheckedBenchmark datasets/Overall results/shared-new-run 1000 3 7 SALTE,DeXOR,Kangaroo,KangarooCompact 1 no
```

`results/shared-new-run` 必须尚不存在，其父目录必须存在。参数依次为：输入CSV/目录、新输出目录、块长、预热次数、测量次数、算法列表、值列索引、是否有表头。块长0表示整文件。默认1000、3、7、四种方法、列1、无表头。目前只处理指定目录的直接CSV文件，目录内按文件名排序。

共同默认协议：

- 所有算法均在每1000值块初始化，保留末尾短块；Kangaroo W=32，SALT自身配置不变。
- 每个文件先完整验证一遍，再做3次预热、7次测量，算法执行顺序轮换。
- 初始化、内存分配、编码结束、flush、共享文件读写计入耗时；输入解析、分块和结果验证不计时。
- 每次解码后逐位检查，所有算法统一允许正负零数值相等并单独计数，其他位差均报失败。
- 报告每文件处理时间的7轮中位数，MB=10^6字节。失败项不产生有效速度结果，不能参与有效算法平均值。
- 压缩大小使用真实文件字节数，包括各自头部和补齐；既给加权bpv，也给各块压缩率的算术平均。

这是含文件I/O的端到端测试，受操作系统文件缓存影响；既有独立内存版本的40.54/85.39 MB/s等数字不能直接拼入这套结果。要判断所有基线的公平排名，还需让其全部使用此入口重跑，并排除未通过还原校验的结果。

## 输出和还原失败

- `summary.csv`：每文件每算法一行，源文件SHA-256、记录数、块数、大小、速度、逐位/正负零差异、第一处不一致及异常。
- `rounds.csv`：每个已测量轮次的时间和状态；有效报告速度只来自通过还原的测量。
- `protocol.txt`：运行参数和计量范围。
- `streams/`：每算法最后处理的临时块，运行期间复用；不当作全数据集压缩档案。

DeXOR 之前出现过的还原差异不在本轮修改范围。统一管线能揭示这类失败，不能使有损回环自动成为有效的无损基线。此次也没有将 Kangaroo 与 DeXOR 的预处理、参数选择或码流串联。
