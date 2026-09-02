# Datasets

This directory contains the inputs retained for the paper experiments:

- `Overall`: the 24-dataset compression benchmark;
- `precision`: decimal-precision variants;
- `preprocess`: preprocessing variants;
- `Proportion`: prefix-length scalability inputs;
- `Overall_shuffled`: shuffled inputs for the temporal-locality study;
- `forCRUD`: point, range, and aggregate operation inputs;
- `vector`: inputs for the optional customized TsFile experiment.

The `deprecated`, `Pilot`, and `Sample` collections remain in the authors' local
workspace but are excluded from Git because no retained paper driver depends on
them. Dataset source URLs, licenses, preprocessing commands, and checksums must
be completed before the final artifact release; retaining a file here does not
by itself establish redistribution permission.
