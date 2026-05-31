# Musashi m68k ISS — vendored sources

This directory contains a snapshot of Karl Stenerud's Musashi m68k
emulator, used as the golden reference ISS for m68k-ooo co-simulation
and fuzzing.

## Upstream

- Repository: https://github.com/kstenerud/Musashi
- Commit:     `313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd`
- Version:    3.32
- License:    MIT (see `m68kconf.h` header — Copyright Karl Stenerud)

## Files

- `m68k.h`, `m68kconf.h` — public API + build configuration
- `m68kcpu.c`, `m68kcpu.h` — core emulator
- `m68kdasm.c`            — disassembler (kept for future decode
                            cross-check work)
- `m68kfpu.c`, `m68kmmu.h` — FPU + MMU emulation
- `m68k_in.c`             — per-opcode handler templates (consumed
                            by `m68kmake`)
- `m68kmake.c`            — generator that reads `m68k_in.c` and
                            emits `m68kops.c` / `m68kops.h`
- `softfloat/`            — Berkeley SoftFloat (used by FPU)

## Build

The parent `Makefile` (at `tb/models/Makefile`) drives the build:

1. Compile `m68kmake.c` to a generator binary.
2. Run it to produce `m68kops.c` / `m68kops.h`.
3. Compile `m68kcpu.c`, `m68kdasm.c`, `softfloat/softfloat.c`,
   and the generated `m68kops.c` into `libmusashi.a`.

## Why vendored?

Musashi is small (< 1 MB of source) and has no runtime dependencies
beyond libc + softfloat.  Vendoring avoids a network fetch at build
time and pins the exact version we co-simulate against.  The full
MIT license text is carried in the file headers and re-stated above.
