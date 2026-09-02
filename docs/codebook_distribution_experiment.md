# `(s, Δu, Δe)` distribution experiment

This experiment tests whether SALT's structural symbols occupy a small, high-probability
region and whether a compact codebook is therefore justified.

## Statistical protocol

- Each CSV file is an independent series; the four history windows are reset between files.
- Column 2 (zero-based index 1) is read, matching `CompBuilder`.
- Zero deltas are reported separately because SALT encodes them with the dedicated zero code.
- For nonzero deltas, the reference window is selected with the encoder's warm-up bit-cost
  model for the entire run. This makes the measurement codebook-independent and avoids
  circular evidence: a codebook cannot manufacture the concentration being measured.
- Global, per-dataset, and 30,000-value block statistics are all emitted.

The main evidence is:

1. Top-K coverage: the probability mass represented by the K most frequent triples.
2. Entropy and effective key count (`2^entropy`): the number of equally likely keys that
   would have the same entropy.
3. Minimum centered 90%, 95%, and 99% regions in the `(Δu, Δe)` plane.
4. Previous-block Top-16/Top-32 hit rates and Top-16 Jaccard similarity, which test whether
   a block-built codebook remains useful in the following block.
5. An adaptive code width selected only on block `t` and evaluated on block `t+1`.
6. Estimated net bit savings, including the 21-bit codebook header, 17 bits per entry,
   and the code index paid by every nonzero symbol (including misses).

## Run

From the project root, compile the experiment and its shared utility:

```powershell
javac -encoding UTF-8 -d target/classes `
  src/main/java/algorithms/SALTE/SALTEUtils.java `
  src/main/java/Experiment/CodebookDistributionExperiment.java
```

Run it over the primary benchmark collection:

```powershell
java -cp target/classes Experiment.CodebookDistributionExperiment `
  datasets/Overall results/codebook_distribution
```

Create the two standalone paper-ready PDF figures:

```powershell
python src/main/python/plot_codebook_distribution.py results/codebook_distribution
```

The plotting program writes `codebook_heatmap.pdf` (with `s=0` and `s=1` merged),
`dataset_top32_coverage.pdf`, and a side-by-side `codebook_distribution_combined.pdf`.
The CSV files in the output directory retain the exact values behind the figures.

## Current `datasets/Overall` result

The checked-in run covers 24 datasets, 1,800,076 values, and 1,427,684 nonzero structural
symbols. It finds 646 raw distinct triples but an entropy-equivalent key count of only 43.13.
The global Top-32 covers 87.02% of symbols, while Top-64 covers 96.50%. A centered region
`|Δu| <= 5, |Δe| <= 4` contains 99.06% of all nonzero symbols.

For the stricter temporal test, the adaptive codebook is selected using block `t` only and
then evaluated on block `t+1`. Across 52 eligible blocks, it reaches a weighted 93.09% hit
rate and saves 1.238 bits per nonzero symbol after charging index bits, the codebook header,
and every entry. Every eligible block has positive net savings in this run.

The fixed-capacity control is also important: Top-16 loses 0.291 bits per symbol on the next
block, while Top-32 saves 0.164 bits. Thus the evidence supports an adaptive codebook and
does not support choosing an arbitrarily tiny fixed codebook.

## Interpretation rule

A codebook is supported when a small K has high coverage, the effective key count is far
below the raw distinct-key count, the centered region covers most symbols, and the
previous-block hit rate remains high across datasets. Report all four rather than selecting
only the strongest aggregate number. Estimated net savings should also be positive after
codebook metadata is included.
