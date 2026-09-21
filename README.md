# SALT

SALT is a structural transition-aware lossless compressor for floating-point
time-series data. SALT+ extends the codec with independently decodable windows
and lightweight indexes for compressed-domain point, range, aggregate, and
update operations.

## Repository layout

The repository separates codec sources, experiment drivers, retained inputs,
publication-facing results, and third-party baseline overlays. See
[`docs/README.md`](docs/README.md) for the directory map.

## Requirements

- JDK 8 or later
- Apache Maven 3.8 or later
- Python 3.9 or later for plotting scripts
- CMake and a C++ compiler only for the bundled ALP baseline

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
results. See [integration notes](docs/kangaroo-integration.md).

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
adds a mode marker and reserves RAW alongside ESC and ZERO; see
[`docs/salt-raw-guard.md`](docs/salt-raw-guard.md) for the active format.
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
| `ULP_BITS` | 4 | 2 | Width of a normal decimal-precision transition. |
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

```text
+---------------------+----------+----------+-----+----------+
| 12-bit header       | window 0 | window 1 | ... | window T-1|
+---------------------+----------+----------+-----+----------+
```

The header contains `WIN_BITS` (4 bits), `EXPONENT_BITS` (4 bits), and
`ULP_BITS` (4 bits). Their defaults are 7, 5, and 2, respectively, so the
initial window size is $2^7=128$ records. The logical header size is **12 bits
(1.5 bytes)**; window data starts at bit offset 12, midway through the second
physical byte.

Windows are independently decodable and concatenated without alignment. The
first record of each window is encoded as either a one-bit zero flag or
`[zero=0][sign][precision:4][exponent:11][mantissa prefix]`. Later records are
encoded relative to the first value as either a one-bit zero-delta flag or
`[zero=0][sign][Delta precision][exponent mode][exponent][mantissa prefix]`.
An overflowed precision transition is followed by a 4-bit absolute precision;
the one-bit exponent mode selects either the normal `EXPONENT_BITS` field or an
11-bit exponent field.

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
| `_window_len.bin` | 13 | `bitlen[i]`, the compressed length of window `i` |
| `_len_fenwick.bin` | 32 | Fenwick node `F_bitlen[i+1]` |

For `T` windows, a sidecar with width `q` occupies
`ceil((40 + qT) / 8)` bytes. With the default widths, the four index payloads
use **86 bits per window**, and their combined physical size is

```text
20 + 8T + ceil(9T/8) + ceil(13T/8) bytes,
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
window_bit_offset = 12 + prefix_sum(F_bitlen, window_id)
window_bit_length = bitlen[window_id].
```

An insert, delete, or update rewrites the affected compressed window, updates
its `cnt` and `bitlen` entries, and applies point updates to both Fenwick trees.
The decoding and recompression work is window-local; if a window's encoded
length changes, replacing its packed segment may still shift subsequent bits
in the main file.

With the current default widths, an individual window can store at most 511
records and 8,191 compressed bits, while each 32-bit Fenwick node can store at
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

The experiment subsets currently retained in Git are described in
`datasets/README.md`. Encoded streams, deprecated data, diagnostic outputs,
temporary files, IDE metadata, and local paper drafts are excluded. See
`results/README.md` for the result-retention policy.

## Third-party software

The `ALP/` directory contains the canonical ALP baseline configured with
16-value vectors. The optional customized TsFile experiment and its setup files
are archived under `abortion/files/`.

## License and citation

License and citation metadata will be added after confirmation by the authors
and the laboratory. Third-party components remain subject to their respective
licenses.

## Local archive

`abortion/` contains removed files at their original relative paths under `files/`,
plus originals of edited files under `before-cleanup/`. It is excluded from Git.
The move list and restoration instructions are in `abortion/README.md`.
Only the timing `main` was removed from `SALTSQL_CRUD`; its query, modification,
rewrite, and index-update methods were retained without changing their behavior.
This cleanup does not complete missing persistence calls in the modification paths.
