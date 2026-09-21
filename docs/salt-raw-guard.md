# SALTE RAW guard

The default `DoubleOurEEncoder` enables RAW fallback. In IDEA's VM options use
`-Dsalt.raw.enabled=true` or `-Dsalt.raw.enabled=false`. An explicit constructor
`new DoubleOurEEncoder(path, boolean)` overrides that property. The decoder reads
the mode from the stream and does not need the switch. The disabled mode retains
the legacy encoder's behavior, including its known failures, for comparisons.

For each reference the enabled encoder checks the fields that will actually be
written, the decoder's mantissa length and reconstruction, decimal residual and
window-state equality, and the final double. Finite doubles must match exactly;
positive and negative zero are equivalent. An unsafe reference is skipped, not
immediately replaced with RAW. Only when all references fail does the original
64-bit value go through RAW. Nonfinite inputs go directly through RAW with their
original bit pattern. There is no cost-based RAW fallback in this version.

RAW and ZERO do not advance either window. RAW does not enter the transition
frequency map, but consumes one record for block scheduling. Transitions that
cannot fit the fixed-width codebook entry are not added to that map in guarded
mode; they remain eligible for ordinary ESC encoding if its fields fit and its
reconstruction passes. This avoids failing at a later codebook boundary.

## Format

RAW-enabled streams prepend one byte `0xF2` to the existing 32-bit header (4-bit
extension marker 15, 4-bit version 2). Legacy streams have no prefix. The marker
reserves window-bits value 15; the present encoder uses window-bits 2. Existing
legacy window sizes below that reserved value are still decoded, including 0.
New RAW streams require the updated decoder. The earlier experimental v1 ESC
subcase format has been removed and is rejected by the decoder.

RAW, ESC and ZERO are three peer reserved codewords. With code width `b`, their
indices are `2^b-3`, `2^b-2`, and `2^b-1`, respectively. Adaptive entries occupy
indices below RAW, with maximum capacity `2^b-3`. RAW writes `WIN(0), RAW, raw64`;
it does not write sign, exponent, precision, or mantissa fields. The initial empty
adaptive codebook has width 2: `01=RAW`, `10=ESC`, `11=ZERO`, and `00` is unused.
The encoder uses this format starting with the first value. After each original
block boundary, it chooses a code width with a minimum of 2 and reserves three
slots. The original candidate-width enumeration and cost objective are retained,
with the required reserved-slot and minimum-width adjustments only.

Even when RAW is never selected, the first 9,999 values now need a two-bit code
instead of the legacy one-bit zero flag. Subsequent codebooks can hold one fewer
adaptive entry at a fixed width, or may choose another width. These costs are part
of the RAW-enabled comparison. Disabled mode keeps the original first-block flag,
two reserved codewords, codebook policy, and byte format. Block scheduling, window
updates, field widths, and arithmetic semantics are otherwise unchanged.

## Audit

Run `Experiment.SALTRawBenchmark` from IDEA, with working directory SALT. Default
input is `datasets/Overall`; default output is a fresh timestamped directory under
`work`. Optional program arguments: `<input-directory> <new-output-directory>`.
The audit explicitly tests both modes regardless of the VM property.

It reads the second CSV column, skips the first row as Main does, includes all
remaining records, and encodes each file as one stream. Internal settings stay at
window size 4 and block size 10000, as found in the current implementation. It
reports serialized bytes, ACB, compressed-size percentage relative to 64-bit raw
input, RAW counts, rejected reference candidates, hashes, and decoder mismatches.
The only allowed bit mismatch is the sign of zero. It also reports the original
`encode()` bit counter for comparison with the existing Main metrics.

Use the current-run RAW-on/off results to assess the guard's size impact, rather
than subtracting numbers from a paper table with other settings. This audit makes
no throughput claim; validating candidates incurs execution cost even when RAW
is never selected.
