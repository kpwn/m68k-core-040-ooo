# Optional Musashi reference model

Musashi is fetched directly from https://github.com/kstenerud/Musashi as a
submodule at `313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd`.
The parent repository no longer bundles its source files.

Initialize explicitly, then build:

```sh
git submodule update --init tools/musashi/musashi
make musashi
```

No network fetch occurs during the build. `prepare.py` checks the pinned
revision and a clean upstream checkout, copies it to ignored `.build/`,
and applies `patches/lockstep.patch`. The submodule is never patched in place.
Delete the disposable `.build/` directory to force preparation again.

The patch preserves the previous oracle's behavior: interrupt acknowledgement,
trace exceptions, instruction-fetch classification, one-instruction stepping,
68040 access-fault delivery and format-7 RTE. It does not modify upstream
`m68kmmu.h`, `m68kfpu.c`, or SoftFloat. The exception-frame additions identify
MAME as their source; retain the notices in ../../THIRD_PARTY_NOTICES.md.

Musashi is a host verification dependency, not synthesized into the FPGA.
Do not bundle the fetched tree, patched build directory or compiled oracle in
our release packages. Upstream terms govern the separately fetched dependency;
the submodule is not a relicensing. Existing published Git history still
contains the former vendored snapshot; this migration does not rewrite history.

When changing the pin, review/rebase the patch, update `PIN` in prepare.py,
compare oracle behavior, and rerun the CPU fast gate and relevant oracle tests.
