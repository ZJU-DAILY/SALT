# Repository structure

- `src/main/java/SALT`: standalone SALT/SALT+ codecs and window-local
  operation implementation.
- `src/main/java/algorithms`: the common codec interface and Java baseline
  adapters used by the compression benchmarks.
- `src/main/java/Experiment`: compression, range-query, aggregate-query, and
  codebook-distribution experiment drivers.
- `src/main/java/org/example`: command-line and paper experiment entry points.
- `src/main/python`: publication-figure scripts.
- `src/test/java`: fast regression tests for the core codec.
- `datasets`: retained experiment inputs; see `datasets/README.md`.
- `results`: publication-facing summaries and figures.
- `ALP`: the canonical bundled ALP baseline, configured with 16-value vectors.
- `third_party`: the pinned overlay for the optional customized TsFile baseline.
- `scripts`: setup helper for the optional TsFile experiment.

Generated compressed streams belong in `storage/`; builds belong in `target/`;
temporary checks belong in `tmp/`. These paths are intentionally ignored.

Historical prototypes under `src/main/java/algorithms/FutureWork` remain in the
authors' local workspace but are excluded from the public source distribution
because no published experiment depends on them.
