# Kangaroo Java 独立复现 v0.2

依据 Shuo Li 等人的 *Kangaroo: Efficient Lossless Floating-Point Compression via Dynamic Reference Selection*, PACMMOD/SIGMOD 2026, DOI: 10.1145/3802076 实现。

**这是独立复现，不是作者代码，不保证与作者位流兼容，当前结果不能代表原论文实现的性能。** 模块可以独立构建，也已通过父项目的算法注册接口接入共同实验管线。接入未修改 SALT、DeXOR 或公共流读写类，详见父项目 `docs/kangaroo-integration.md`。

## 已实现

- Compact：按尾随零数分组，维护组内相邻参考的 XOR 前导零数，按 Algorithm 1 跳过不可能改进的候选。
- Fast：一次前导零查询选取候选的独立实现，具体维护规则见下文。
- 按图 6 实现三分支参考编码；前导零使用 `{0,8,12,16,18,20,22,24}` 的 3-bit 表示；省略当前值有效中心段末尾已知的 `1`。
- 按图 8 实现不擦除、擦除、位翻转三种情况；按式 (21) 反推十进制精度。
- 在擦除之前按原始位模式识别连续重复值，编码游程。
- 独立块、增量 add/read API、CLI、完整位模式比较和边界测试。

默认 W=32；基准命令默认每块 1,000 值。Java 8 兼容，编解码核心仅依赖 JDK。CLI 的 CSV 解析依赖 Commons CSV。

v0.2 已修改 Fast 查询、精度反推和实验入口，并补入论文示例、独立搜索 oracle、缺失值及块平均测试。**尚未确认的格式细节没有用猜测替换。** 逐项状态及本次修改理由见 [ALIGNMENT.md](ALIGNMENT.md)。KGRJ 块格式仍为 v1，能够读取 v0.1 文件。

## 构建和使用

核心 Java 包名为 `compression.kangaroo`，独立模块 Maven groupId 同为 `compression.kangaroo`；名称不表示任何机构或原作者归属。父项目入口 `org.example.Main` 默认同时运行 SALTE 和 Kangaroo Fast，详见 `../docs/kangaroo-integration.md`。

在此目录运行：

```powershell
mvn test
mvn package
java -jar target/kangaroo-reimplementation-0.2.0-all.jar
```

CSV 数据逐位验证和压缩大小统计：

```powershell
java -Xmx1g -jar target/kangaroo-reimplementation-0.2.0-all.jar benchmark ../datasets/Overall results.csv both 1000 1 no
```

参数顺序：CSV 文件或目录、输出 CSV、`both|fast|compact`、块长度、从 0 开始的值列索引、`auto|yes|no` 表头策略。`auto` 仅在首条记录的值列不能解析为 double 时将其视作表头；已知数据格式时建议显式使用 `yes` 或 `no`。后续非法数值报错，不跳过。CSV 文本中的 `NaN` 无法携带自定义 NaN payload；需要保存 payload 时使用 raw API 或二进制文件。

空行、空单元格、缺失列均报错，不再由 CSV 库默认忽略空行。`auto` 是便利选项，论文协议入口禁止使用它。

按论文明确给出的分块与平均口径运行单个数据集：

```powershell
java -Xmx1g -jar target/kangaroo-reimplementation-0.2.0-all.jar paper-benchmark Stocks-USA.csv stocks-report.csv 0 no fast
```

该入口固定 W=32、块长1000、开启擦除和 VL-RLE；列号与有无表头必须显式指定，默认 Fast。最后不足1000值的块保留并单独报告（论文未明确尾块策略，这是本实现选择）。输出每个块和 ALL 汇总行：

- `mean_block_*_ratio`：每块 compressed/original 的算术平均，数值越小越好；
- `weighted_*_ratio`：总压缩位数/总输入位数；
- payload 不含自定义20字节头及补齐，file 包含它们；
- 3次完整预热、7次完整测量，逐块取时间中位数；同时报告块吞吐算术平均和总字节/块中位时间之和，MB=10^6 bytes；
- SHA-256、列号、表头/缺失值/尾块策略、JVM版本、分支计数和逐位还原结果。

这些预热、重复次数和时间统计是复现程序的显式选择，论文没有给出这些完整细节。计时包含对象分配、编解码初始化、输出分配和 finish；排除 CSV、文件读写、文件摘要及解码结果比对。每轮都在计时结束后逐位验证。正式计时应独占机器运行，不能与其他压力任务并行；该入口也不等于已复现作者硬件上的吞吐。

压缩和解压原始 binary64 文件（大端，每值 8 字节）：

```powershell
java -jar target/kangaroo-reimplementation-0.2.0-all.jar encode input.f64be output.kgr fast 32
java -jar target/kangaroo-reimplementation-0.2.0-all.jar decode output.kgr recovered.f64be
```

输出必须尚不存在，避免覆盖输入或历史结果。二进制 CLI 当前把整个文件编码为一个块，块保存在内存中；大流应由调用者按块使用 API。`finish()` 必须调用，以提交末尾游程。已 finish 的编码器不可继续添加。

```java
import compression.kangaroo.Options;
import compression.kangaroo.KangarooEncoder;
import compression.kangaroo.KangarooDecoder;

Options options = new Options(32, Options.Search.COMPACT, true, true);
KangarooEncoder encoder = new KangarooEncoder(options);
encoder.add(2.87);
encoder.add(11.44);
encoder.addRaw(0x7ff8000000001234L); // 保留 NaN payload
byte[] block = encoder.finish();
KangarooDecoder decoder = new KangarooDecoder(block);
while (decoder.hasNext()) {
    long raw = decoder.readRaw();
    // 或 readDouble()；如需逐位比较，使用 readRaw()。
}
```

## 复现选择及与论文的差异

### 1. 精度定义和式 (22) 的修正

十进制精度 alpha 定义为 `BigDecimal.valueOf(value).stripTrailingZeros().scale()` 与 0 的较大者，即 Java 最短回环十进制表示的小数位数，不保留输入文本多余的末尾零。

论文例子 `11.44 -> 11.4375 -> 11.46875` 的最终值满足 E=3、trail=44、L(alpha)=42、L(alpha-1)=45。式 (21) 的区间条件满足，但式 (22) 返回 `floor((52-3-44)*log10(2))=1`，与 alpha=2 不符。

本实现直接寻找满足式 (21) 的唯一非负整数 alpha。通过 `BigInteger` 精确预计算 `ceil(alpha*log2(10))`，避免浮点对数在整数边界上的误差。撤销位翻转后，以 `new BigDecimal(double)` 获取精确二进制值对应的十进制数，再用 `RoundingMode.UP` 恢复到 alpha 位。该恢复方式是明确记录的实现选择，不能冒称作者实际采用的方式。

v0.2 为每个可能的 `need=52-E-trail` 预计算最小满足 `ceil(alpha*log2(10)) >= need` 的 alpha。解码时一次查表并验证式 (21)，替代 v0.1 的二分搜索；不使用浮点近似反函数。

编码器调用同一恢复函数，确认恢复位模式与输入完全相同后才接受擦除。验证失败或翻转超出 52 位尾数时，使用论文的 `111` 不擦除标签，把原位模式交给参考编码层；**并非每次都另写一个完整 64-bit RAW 值**。

### 2. 搜索默认值与 Fast 查表

Algorithm 1 的最优参考初值为 NULL，本实现明确设为同尾随零组中的最新值。相同收益时保留较新参考。

Fast 按尾随零数分组，使用 `shortcut[trail][lead]`。追加新组头 b 时，设 a 为旧组头、ell=lead(a XOR b)：

- 小于 ell 的表项保留；
- ell 对应表项指向 a；
- 大于 ell 的表项清空；
- ell=64（相同位模式）时保留表；
- 查询时以 lead(current XOR newest) 为键，候选仍位于窗口内就直接采用；表项不变量保证其前导零严格改善，不再执行额外一次 XOR 来复查。

这是基于文中“第一跳严格改进”思路的独立解释。它不是完整最优搜索；Compact 才对同尾随零组最大化精确前导零数。Fast 查询 O(1)，更新至多处理固定 64 个表项，不随 W 线性增长；内存 O(W + 65*64)。不声称作者使用了完全相同的表更新方式。

### 3. 窗口、游程与码流

窗口按已编码的非重复游程值推进，保存擦除/翻转后的位模式；RLE 展开的重复项不推进历史。参考索引是环形窗口槽位，槽位有效性通过逻辑序号检验。窗口大小允许 1 到 65536 的 2 的幂。

论文未完整给出 VL-RLE 长度格式。本实现采用 `110 + unsigned LEB128(额外重复次数)`，出现任何至少一个额外重复时都使用 RLE。尾部游程在 finish 时提交。重复判断为位模式相同，不合并正负零或不同 NaN payload。

保留图6的 `00+index` 历史相同值分支。§6.4 的移除重复值分支表述处于 Gorilla 消融实验上下文，不能据此断定最终 Kangaroo 删除该分支。`1.25, 2.5, 1.25` 中第三个值不是连续游程，仍需历史引用；正式测试覆盖此区别。最终作者的两层标签整合方式仍待代码或作者说明确认。

图 6 的“center”按当前值的有效中心位解释：复制参考的共同前导位，填入当前值中心，补上已知的最后一位 `1` 和尾随零。相同尾随零时不能误把 XOR 中心的末位当成 `1`，因为对应 XOR 位是 `0`。回退分支将共享前导长度限制在 `63-trail` 以内，避免共同前缀与尾随零区重叠。

### 4. 特殊值支持

零、次正规数、无穷大和 NaN 不擦除；保持原始位模式，包括 `-0.0` 和 NaN payload。擦除长度仅允许 1..52，位翻转也只能作用于显式尾数。

`+0.0` 的尾随零数为 64，无法用 6 位表示。回退分支保留本来不可能出现的 `(trail=63, lead-code=7)` 表示 +0，不写 center。这个扩展以及特殊值规则不是论文明确给出的协议。

## 独立 KGRJ v1 块格式

固定 20 字节头，所有多字节字段大端：

| 字段 | 大小 | 内容 |
|---|---:|---|
| magic | 4 bytes | ASCII `KGRJ` |
| version | 1 byte | 1 |
| flags | 1 byte | bit0 Fast、bit1 erasure、bit2 RLE |
| windowBits | 1 byte | log2(W) |
| reserved | 1 byte | 0 |
| count | 4 bytes | 非负记录数，最多 Integer.MAX_VALUE |
| payloadBits | 8 bytes | 有效负载位数，不含末尾补齐 |

负载按高位到低位排列，尾部补零到整字节。首个非游程记录为擦除标签 + 64-bit 转换后位模式。后续普通记录为擦除标签 + 参考编码。

擦除标签：`0` 擦除；`10` 位翻转；`111` 不擦除；`110` 游程。

参考标签：`00 + index` 相同转换值；`1 + index + lead-code + center` 共享尾随零；`01 + trail(6) + lead-code(3) + center` 使用直接前驱。center 位数 `64-lead-trail-1`，其中 lead 为查表后的值。

格式、长度、引用和游程越界会报错；没有校验和，不保证检测所有负载位翻转。空块只有 20 字节头，count 和 payloadBits 均为 0。

## 验证与性能解释

测试包括论文例子、已发现的 SALT/DeXOR 失败输入、随机 binary64 位模式、随机十进制、所有二次幂指数附近的相邻值、特殊值、长游程、窗口回绕、Compact 与穷举对照、截断/非法格式，以及 CSV 首个数值保留。

基准逐块执行 `readRaw()==inputRaw`，不使用 epsilon，也不忽略正负零。报告同时给出 payload bpv 和包括自定义块头/字节补齐的 file bpv。编码/解码时间不包括 CSV 读取和数值验证，但是单次串行诊断；没有 JIT 预热、多轮测量或硬件控制，不能用来复述论文吞吐优势。

精度恢复采用 BigDecimal，当前版本以正确性与可审查性为优先。计时包含实际擦除安全检查开销。使用不同实现选择、窗口语义、数据切片或块长度的结果不能直接作为作者实现的性能证据。
