# SALT

SALT is a structural transition-aware lossless compressor for floating-point
time-series data. SALT+ extends the codec with independently decodable windows
and lightweight indexes for compressed-domain point, range, aggregate, and
update operations.

## Repository layout

Codec sources and experiment drivers are under `src`, with Kangaroo's core
implementation under `kangaroo-java`. Dataset files, results, and the local
`abortion` archive are excluded from Git.

## Requirements

- JDK 8 or later
- Apache Maven 3.8 or later
- Python 3.9 or later for plotting scripts

## Build

```bash
mvn clean package
```

The command produces `target/salt-1.0-SNAPSHOT-all.jar` with all Java runtime
dependencies included.

Run the regression tests with:

```bash
mvn test
```

Unused TsFile integration and CRUD benchmark drivers are archived under `abortion/`.
The default build retains the original SALT/baseline entry points and CRUD implementation.

## Compression benchmark

The default entry point recursively processes CSV files in the input path and
writes compressed streams and CSV summaries to separate directories:

```bash
java -jar target/salt-1.0-SNAPSHOT-all.jar \
  -in datasets/Overall \
  -out storage \
  -log results/Overall \
  -m SALTE
```

The current implementation names the core codec `SALTE` and the indexed
extension `SALTSQL`. These internal names will be aligned with the paper names
`SALT` and `SALT+` in a compatibility-preserving cleanup.

## Shared, checked benchmark (including Kangaroo)

The independent Kangaroo Java implementation is now registered alongside DeXOR
and SALTE as `Kangaroo` (Fast) and `KangarooCompact`. The root Maven build includes
the sources and tests from `kangaroo-java`; no separate install is needed.
Both adapters use the existing `StreamWriter` / `StreamReader` file I/O and retain
byte-identical KGRJ v1 output. They do not apply DeXOR preprocessing.

For new comparisons, use the checked driver:

```bash
java -Xms1g -Xmx1g -cp target/salt-1.0-SNAPSHOT-all.jar Experiment.CheckedBenchmark \
  datasets/Overall results/shared-new-run 1000 3 7 \
  SALTE,DeXOR,Kangaroo,KangarooCompact 1 no
```

The output directory must not exist, and its parent must exist. Arguments after
the directory are block size (0 = whole file), warmup passes, measured passes,
comma-separated methods, zero-based numeric column and explicit header policy.
The driver accepts a CSV file or the immediate CSV files in a directory.
It writes `summary.csv`, `rounds.csv`, `protocol.txt`, and temporary streams.
It retains the first numeric row and short final block, rejects missing input,
includes codec construction/finalization/flush and file I/O in both timers,
uses MB = 1,000,000 bytes, and compares raw bits after timing (signed zeros are
accepted as numerically equal and counted). Failed codecs have a failure status
and no valid throughput result. CSV loading and verification are not timed.

This is a file-I/O benchmark (including OS page-cache effects), not a pure codec
benchmark. Kangaroo's adapter includes its existing memory buffering and the
copy to/from shared streams. Compression sizes include each codec's own framing.
The earlier standalone memory throughput numbers must not be mixed with these
results.

The legacy `Main`/`TestBuilder` entry also recognizes `-m Kangaroo`, but retains
its original header skipping, timing and validation behavior for reproducibility;
use the checked driver for new comparisons.

## Binary layout

This section documents the current formats produced by the Java implementations
referenced above. The formats are internal experiment formats rather than a
versioned interchange specification. Unless stated otherwise, fields are packed
MSB-first without byte alignment, and the final byte is padded with zero bits.
All widths below are in **bits**.

### SALT stream (legacy mode)

RAW is enabled by default, with a 10,000-value internal block. The enabled format
adds a mode marker and reserves RAW alongside ESC and ZERO.
The layout below describes the legacy mode selected by `-Dsalt.raw.enabled=false`.

The benchmark codec implemented by
[`DoubleOurEEncoder`](src/main/java/algorithms/SALTE/encoder/DoubleOurEEncoder.java)
writes one stream in the following order:

```text
+-------------------+--------------------+------------------+-----+
| 32-bit file header| encoded records    | codebook segment | ... |
+-------------------+--------------------+------------------+-----+
```

The codebook segments are embedded at the periodic boundaries determined by
`BLOCK_SIZE`; they are not stored in a separate file.

#### File header

| Field | Width | Default | Meaning |
|---|---:|---:|---|
| `WIN_BITS` | 4 | 2 | Width of a reference index; the sliding window contains $2^{WIN\_BITS}=4$ candidates. |
| `EXPONENT_BITS` | 4 | 3 | Width of a normal exponent transition. |
| `ULP_BITS` | 4 | 3 | Width of a normal decimal-precision transition. |
| `BLOCK_SIZE` | 20 | 10,000 | Codebook refresh period. |

The SALT header is therefore exactly **32 bits (4 bytes)**. The benchmark
stream does not store the number of records; its decompression driver obtains
that number from the corresponding input dataset.

#### Record payload

Each record starts with a `WIN_BITS`-bit reference index. Before an adaptive
codebook becomes active, a nonzero delta is represented as

```text
[reference][zero=0][sign][Delta exponent][Delta precision][mantissa prefix]
```

A zero delta contains only `[reference][zero=1]`. Exponent and precision
transitions use sign-magnitude encoding. If a transition exceeds its normal
field, the sign-magnitude negative-zero pattern acts as an escape marker and is
followed by an 11-bit absolute exponent or a 4-bit absolute decimal precision,
respectively. The mantissa prefix has a data-dependent width between 0 and 52
bits.

With an active codebook, each record uses a `c`-bit code:

```text
codebook hit: [reference][code][mantissa prefix]
escape:       [reference][ESC][sign][Delta exponent][Delta precision][mantissa prefix]
zero delta:   [reference][ZERO]
```

Codes `0` through `K-1` identify codebook entries in stored order;
`ESC = 2^c-2` and `ZERO = 2^c-1` are reserved.

#### Codebook segment

```text
+------------------+----------------+------------------------------------+
| code width c (5) | entry count K  | K entries                         |
|                  | (16)           | sign (1), Delta u (5), Delta e (11)|
+------------------+----------------+------------------------------------+
```

The 5-bit `c` field specifies the code width used by subsequent records, and
the 16-bit `K` field specifies the number of stored structural transitions.
Each 17-bit entry represents `(sign, Delta u, Delta e)`, where `Delta u` and
`Delta e` are sign-magnitude values. A segment occupies exactly
`21 + 17K` bits. Because it shares the surrounding bitstream without padding,
its isolated byte equivalent is `ceil((21 + 17K) / 8)` bytes.

### SALT+ data and indexes

The indexed implementation in
[`SALTSQL`](src/main/java/SALT/SALTSQL.java) and
[`SALTSQL_CRUD`](src/main/java/SALT/SALTSQL_CRUD.java) stores a column as five
files:

```text
<base>.bin                 compressed windows
<base>_window_num.bin      record count cnt[i] of each window
<base>_num_fenwick.bin     Fenwick tree over cnt[i]
<base>_window_len.bin      compressed bit length bitlen[i] of each window
<base>_len_fenwick.bin     Fenwick tree over bitlen[i]
```

Despite their historical names, `window_num` stores record counts and
`window_len` stores compressed lengths in **bits**, not bytes.

#### Main compressed file

New SALT+ files use a **20-bit versioned header**:
`marker=0:4 | version=1:4 | WIN_BITS:4 | EXPONENT_BITS:4 | ULP_BITS:4`.
The default parameter values are 7, 3, and 3, giving 128 records per initial
window. Windows are concatenated without byte alignment, starting at bit 20.
Legacy files with a nonzero first nibble retain their 12-bit header and can
still be read. Re-encode legacy files before CRUD modification; formats cannot
be mixed within one file. Standalone file decompression uses the record-count
sidecar to distinguish records from byte padding.

The fixed codebook follows the paper's SALT+ design: `RAW=01`, `ESC=10`, and
`ZERO=11` (`00` is invalid). Every record, including ZERO and RAW, contributes
to the window's record count and stored bit length.

- **RAW:** the two-bit codeword followed by the original 64-bit double, with
  no structural fields. It is used if ordinary encoding cannot reconstruct
  the input exactly; signed zeros are treated as equivalent.
- **ZERO:** no payload. At the start of a window it represents zero; otherwise
  it represents the fixed reference value (zero residual).
- **ESC:** for the first record, sign, absolute precision (4 bits), absolute
  exponent (11-bit sign-magnitude), and mantissa prefix. Later records encode
  the residual's sign and precision/exponent transitions relative to the
  window's first value, followed by the mantissa prefix. Transition fields use
  `ULP_BITS` and `EXPONENT_BITS`; the sign-magnitude negative-zero sentinel
  introduces a 4-bit absolute precision or 11-bit absolute exponent.

The encoder verifies the complete candidate record with the shared decoder
before accepting it, including field representability and final reconstruction.
The first value remains the window reference even when stored as RAW; subsequent
ZERO/RAW records never replace it. The sequential double API also preserves
non-finite values through RAW (a non-finite first value requires RAW for all
following values in that window). The indexed CRUD API uses `BigDecimal` and
supports finite binary64 values, not NaN or infinity.

#### Index sidecars

Every sidecar has the same packed layout:

```text
+------------------+-----------------------+---------------------------+
| value width (8)  | number of windows T   | T fixed-width values      |
|                  | (32, big-endian)      |                           |
+------------------+-----------------------+---------------------------+
```

Thus, each sidecar has a **5-byte metadata header** followed by its packed
payload:

| File | Default value width | Entry `i` |
|---|---:|---|
| `_window_num.bin` | 9 | `cnt[i]`, the current number of records in window `i` |
| `_num_fenwick.bin` | 32 | Fenwick node `F_cnt[i+1]` |
| `_window_len.bin` | 14 | `bitlen[i]`, the compressed length of window `i` |
| `_len_fenwick.bin` | 32 | Fenwick node `F_bitlen[i+1]` |

For `T` windows, a sidecar with width `q` occupies
`ceil((40 + qT) / 8)` bytes. With the default widths, the four index payloads
use **87 bits per window**, and their combined physical size is

```text
20 + 8T + ceil(9T/8) + ceil(14T/8) bytes,
```

including the four 5-byte headers and per-file byte padding. The current Java
access layer additionally loads `cnt` and `bitlen` into two `long[T]` arrays
(approximately `16T` bytes of array payload) while accessing both Fenwick
trees directly on disk.

#### Logical-to-physical localization

For a zero-based logical position `p`, SALT+ performs a lower-bound search on
`F_cnt` for `p+1` to locate the containing window. A prefix sum then gives the
intra-window record offset. The corresponding physical location is

```text
window_bit_offset = header_bits + prefix_sum(F_bitlen, window_id)
window_bit_length = bitlen[window_id].
```

An insert, delete, or update rewrites the affected compressed window, updates
its `cnt` and `bitlen` entries, and applies point updates to both Fenwick trees.
The decoding and recompression work is window-local; if a window's encoded
length changes, replacing its packed segment may still shift subsequent bits
in the main file.

With the current default widths, an individual window can store at most 511
records and 16,383 compressed bits, while each 32-bit Fenwick node can store at
most $2^{32}-1$. A CRUD operation fails rather than silently overflowing when
an updated value exceeds one of these fixed-width fields.

## Experiment drivers

The following Java entry points implement the main paper experiments:

- `Experiment.CodebookDistributionExperiment`
- `org.example.Precision`
- `org.example.Preprocess`
- `org.example.Proportion`
- `org.example.ShuffleDataset`

Python plotting utilities are located in `src/main/python`.

## Data and generated files

Dataset files are not distributed with this repository; only the placeholder
`datasets/.gitkeep` is tracked. Obtain datasets from the original sources cited
in the paper and prepare local CSV inputs under `datasets/Overall/` to use the
example commands above. Dataset files and experiment results are excluded from
Git, along with encoded streams, temporary files, IDE metadata, and local paper drafts.

### Dataset sources

The following mapping follows Table 1 and the references of the SALT paper.
Dataset names and abbreviations are those used in the paper; reference numbers
refer to that manuscript. Links identify the cited source, not necessarily a
ready-to-run copy of the experimental subset.

| Abbreviation | Dataset | Source cited in the paper | Paper reference |
|---|---|---|---|
| KPI | KPI_18fb | Ren et al., [Time-Series Anomaly Detection Service at Microsoft](https://arxiv.org/abs/1906.03821), KDD 2019 (paper). | [46] |
| CT | City-temp | [Daily Temperature of Major Cities](https://www.kaggle.com/datasets/sudalairajkumar/daily-temperature-of-major-cities), Kaggle. | [4] |
| WS | Wind-Speed | [2D Wind Speed and Direction](https://data.neonscience.org/data-products/DP1.00001.001/RELEASE-2022), NEON, release 2022. | [8] |
| IR | IR-bio-temp | [IR Biological Temperature](https://data.neonscience.org/data-products/DP1.00005.001/RELEASE-2022), NEON, release 2022. | [13] |
| PM | PM10-dust | [Dust and Particulate Size Distribution](https://data.neonscience.org/data-products/DP1.00017.001/RELEASE-2022), NEON, release 2022. | [11] |
| DPT | Dew-point-temp | [Relative Humidity Above Water On-Buoy](https://data.neonscience.org/data-products/DP1.20271.001/RELEASE2022), NEON, as cited in the paper. | [14] |
| CA | California | [Historical EMS Hourly Load](https://www.caiso.com/library/historical-ems-hourly-load), California ISO; cited as OASIS: System Demand. | [26] |
| HRL | hrl_load_metered | [Hourly Load: Metered](https://dataminer2.pjm.com/feed/hrl_load_metered), PJM Data Miner. | [21] |
| SUK | Stocks-UK | [Financial Data Set Used in INFORE Project](https://zenodo.org/record/3886895), Zenodo. | [5] |
| SUSA | Stocks-USA | [Financial Data Set Used in INFORE Project](https://zenodo.org/record/3886895), Zenodo. | [5] |
| SDE | Stocks-DE | [Financial Data Set Used in INFORE Project](https://zenodo.org/record/3886895), Zenodo. | [5] |
| BP | Bitcoin-price | [InfluxDB 2.0 Sample Data](https://github.com/influxdata/influxdb2-sample-data). | [17] |
| AP | Air-pressure | [Barometric Pressure](https://data.neonscience.org/data-products/DP1.00004.001/RELEASE-2022), NEON, release 2022. | [9] |
| BM | Bird-migration | [InfluxDB 2.0 Sample Data](https://github.com/influxdata/influxdb2-sample-data). | [17] |
| BW | Basel-wind | [Historical Weather Data for Basel](https://www.meteoblue.com/en/weather/archive/export/basel_switzerland), meteoblue. | [16] |
| ER | exchange | Lai et al., [Modeling Long- and Short-Term Temporal Patterns with Deep Neural Networks](https://doi.org/10.1145/3209978.3210006), SIGIR 2018 (paper). | [35] |
| BT | Basel-temp | [Historical Weather Data for Basel](https://www.meteoblue.com/en/weather/archive/export/basel_switzerland), meteoblue. | [16] |
| WF | waveform_float | [FDSN Dataselect Web Service](https://service.iris.edu/fdsnws/dataselect/1/), IRIS. | [20] |
| EVC | Vehicle_charging | [Electric Vehicle Charging Dataset](https://www.kaggle.com/datasets/michaelbryantds/electric-vehicle-charging-dataset), Kaggle. | [12] |
| BL | Blockchain-tr | [Bitcoin Transactions](https://gz.blockchair.com/bitcoin/transactions), Blockchair. | [10] |
| FP | Food-price | [Global Food Prices Database (WFP)](https://data.humdata.org/dataset/wfp-food-prices), Humanitarian Data Exchange. | [6] |
| SSD | SSD-bench | [SSD and HDD Benchmarks](https://www.kaggle.com/datasets/alanjo/ssd-and-hdd-benchmarks), Kaggle. | [15] |
| CLA | City-lat | [World Cities of Different Countries](https://www.kaggle.com/datasets/kuntalmaity/world-city), Kaggle. | [7] |
| CLO | City-lon | [World Cities of Different Countries](https://www.kaggle.com/datasets/kuntalmaity/world-city), Kaggle. | [7] |

For KPI and ER, the manuscript cites research papers rather than direct dataset
download URLs; the links above lead to those papers.
The source citations alone do not specify all column selections, date ranges,
or preprocessing steps needed to reconstruct the exact experimental inputs.

## Baseline implementation sources

The table records the Java implementations used in this repository. For methods
integrated through DeXOR, the links identify the implementation source rather
than implying that we obtained code directly from each method's original authors.

| Baseline | Implementation source | Local source |
|---|---|---|
| ALP | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/ALP); distinct from the native C++ ALP implementation. | [`ALP`](src/main/java/algorithms/ALP) |
| Chimp | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/Chimp). | [`Chimp`](src/main/java/algorithms/Chimp) |
| Chimp128 | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/Chimp128). | [`Chimp128`](src/main/java/algorithms/Chimp128) |
| Gorilla | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/Gorilla). | [`Gorilla`](src/main/java/algorithms/Gorilla) |
| Elf | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/Elf). | [`Elf`](src/main/java/algorithms/Elf) |
| ElfPlus | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/ElfPlus). | [`ElfPlus`](src/main/java/algorithms/ElfPlus) |
| Camel | Java implementation from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/Camel); the source also credits [yoyo185644/camel](https://github.com/yoyo185644/camel). | [`Camel`](src/main/java/algorithms/Camel) |
| DeXOR | Java implementation from the [DeXOR repository](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms/DeXOR). | [`DeXOR`](src/main/java/algorithms/DeXOR) |
| Kangaroo | Independent Java reimplementation following the paper, with Fast and Compact modes; not the authors' implementation and not compatible with their wire format. | [`Core`](kangaroo-java/src/main/java/compression/kangaroo), [`benchmark adapters`](src/main/java/algorithms/Kangaroo) |
| ElfStar / SElfStar | Additional Java implementations from [DeXOR](https://github.com/SuDIS-ZJU/DeXOR/tree/master/src/main/java/algorithms); their source credits [Spatio-Temporal-Lab/SElfStar](https://github.com/Spatio-Temporal-Lab/SElfStar). | [`ElfStar`](src/main/java/algorithms/ElfStar), [`SElfStar`](src/main/java/algorithms/SElfStar) |

ElfStar and SElfStar are retained in the codebase in addition to the nine paper
baselines. All implementations use the local benchmark interfaces; these source
attributions do not imply identical experiment settings or results to upstream.

## Third-party software

The native ALP tree is archived locally under `abortion/upload-layout-20260921/ALP`.
The Java ALP adapter remains in `src/main/java/algorithms/ALP`. The optional
customized TsFile experiment and its setup files are archived under `abortion/files/`.

## License and citation

License and citation metadata will be added after confirmation by the authors
and the laboratory. Third-party components remain subject to their respective
licenses.

