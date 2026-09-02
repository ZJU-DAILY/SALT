# ALP baseline used by SALT

This directory contains the canonical ALP baseline bundled with SALT. It is
derived from the official [`cwida/ALP`](https://github.com/cwida/ALP) project at
commit `31ca0ed11c93c99d3f5b5c30e01a3e1c3832d3ce` and configured for 16-value
vectors.

Configuration:

- vector size: 16 values;
- vectors per row group: 10;
- row-group size: 160 values;
- samples per vector: 16;
- maximum `(exponent, factor)` combinations: 4.

`SAMPLES_PER_VECTOR` is set to 16 because a vector contains only 16 values.
Leaving the original value of 32 would make the sampler skip all but the first
short vector in a row group.

The official FastLanes generated FFOR/UnFFOR kernels are specialized for
1,024 values. This baseline uses a configurable scalar bit-packing
implementation, so a 16-value vector is encoded and
decoded without accessing beyond its buffers. Compression and decompression
speed results are therefore not comparable to the official SIMD ALP
implementation. The `size` column remains suitable for the requested vector
size experiment.

The benchmark performs a lossless round-trip check for every complete
16-value vector and excludes only a final incomplete vector.
