# Repository structure

- `src/main/java/SALT`: SALT+ codecs and window-local
  operation implementation.
- `src/main/java/algorithms`: the common codec interface and Java baseline
  adapters used by the compression benchmarks.
- `src/main/java/Experiment`: compression and codebook-distribution experiment drivers.
  CRUD/query benchmark drivers are archived.
- `src/main/java/org/example`: command-line and paper experiment entry points.
- `src/main/python`: publication-figure scripts.
- `src/test/java`: fast regression tests for the core codec.
- `datasets`: empty placeholder; supply local inputs before running experiments.
- `results`: local experiment outputs, excluded from Git.
- `abortion`: archived files and originals of edited files; excluded from Git.

Generated compressed streams belong in `storage/`; builds belong in `target/`;
temporary checks belong in `tmp/`. These paths are intentionally ignored.

Unused prototypes, standalone legacy SALTE files, optional TsFile integration,
CRUD/query experiments, and paper drafts are stored under `abortion/files/`.
CRUD operation methods and the original Main entry remain in the active sources.
