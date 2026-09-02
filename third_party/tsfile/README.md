# Customized Apache TsFile baseline

The optional TsFile experiment uses Apache TsFile commit
`38a847ddae3e26030208fbee9bcbc61e98e342f9` plus the files under `overlay/`.
The overlay adds the DeXOR encoding used by `Experiment.TsfileTestBuilder`.
The public TsFile 2.2.0 artifact does not contain this encoding and therefore
cannot compile that experiment.

On Windows, reconstruct and install the customized dependency with:

```powershell
.\scripts\setup_custom_tsfile.ps1
mvn -Pcustom-tsfile clean package
```

The first command clones the pinned Apache source into `third_party/_work/`,
applies the overlay, and installs `org.apache.tsfile:tsfile:2.2.0-SNAPSHOT` in
the local Maven repository. The copied source files retain their Apache license
headers; the complete upstream license is available in the reconstructed tree.
