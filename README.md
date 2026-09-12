# m68k-core-040-ooo

An out-of-order, superscalar Motorola 68040 CPU core written in
[SpinalHDL](https://github.com/SpinalHDL/SpinalHDL), built on a plugin
(Fiber + Database) backbone.

It is a real core, not a model: it boots Mac OS to the Finder on FPGA, inside a
Quadra 700 SoC, executing the stock Q700 ROM unmodified.

## Status

| | |
|---|---|
| Target | MC68040 user + supervisor, FPU, MMU |
| Language | SpinalHDL (Scala), elaborated to Verilog |
| Scale | ~108 source files, ~56k lines |
| Verification | 274 ScalaTest specs · 921 assembled 68k programs · Musashi lock-step |
| Silicon | Xilinx KU5P, ~100 MHz core clock, timing-closed |
| Boots | Mac OS to the Finder on the stock Quadra 700 ROM |

The core is under active development. Known defects and deliberate deviations are
tracked in `docs/BUG_*.md` and `docs/KNOWN_DEVIATION_*.md` rather than hidden — see
[Known gaps](#known-gaps).

## Architecture

Two-wide decode and retire, register-renamed, out-of-order issue, precise exceptions.

```
FetchAlign ─ BTB/FTB/gshare/RAS ─ Icache ─ Predecode
     │
  DecodeStage ── microcode engine (multi-µop macros: MOVEM, FMOVEM.X, memory-indirect EAs)
     │
  RenameStage ── RAT + freelists for INT / NZVC / X / FP / FPCC
     │
  Dispatch ── RobPlugin (64 entries) ── IssueQueue (INT / EA / MEM / CPLX clusters)
     │
     ├── AluEu        integer ALU, shifts, bitfield, BCD
     ├── BranchEu     Bcc/DBcc/Scc/FBcc, ibranch, RTS/RTE
     ├── DivEu/CPLX   divide, multiply, and the FPU (shared iterative lane)
     └── LsEu         AGU + store queue + load queue
                          │
                   Dcache ─ DTLB ─┐
                   Icache ─ ITLB ─┴─ hardware table walker (U/M writeback)
```

Key structures (`Config.scala` defaults): ROB 64, 50 physical integer registers,
separate NZVC/X/FPCC rename files, 8-deep load and store queues, L1I 16 KB 4-way
(64 B lines), 4-way × 16-set TLBs, 128-entry BTB, 16-entry RAS, gshare (16 bits,
2048 entries).

Plugins communicate only through `host[Service]` interfaces and `Global` Database
keys — never by reaching into each other. See `AGENTS.md` for the invariants.

### Floating point

The FPU implements **every opmode a real 68040 executes in hardware** — all eight
precision families in base/single/double form (FMOVE, FSQRT, FABS, FNEG, FDIV, FADD,
FMUL, FSUB) plus FINT, FINTRZ, FCMP and FTST. Everything else — transcendentals,
FMOD/FREM/FSCALE/FGETEXP, FSINCOS, FSGLMUL/FSGLDIV — correctly traps to the FPSP via
vector 11, exactly as silicon does. Implementing those in hardware would be a
*deviation* from the 68040, not an improvement.

IEEE exception delivery (vectors 48–55) is implemented with architectural vector
numbering, gated on the FPCR enable bits, and FSAVE emits the 52-byte version-`$41`
frame the Q700 FPSP requires.

## Building and testing

```sh
make compile        # sbt compile
make test-fast      # fast gate (excludes slow/verilator/board tags)
make test           # full suite
make verilog        # elaborate generated/M68kCore.v
```

`sbt` may not be on PATH; invoke as `make SBT=~/sbt/bin/sbt <target>`.

The ported-program corpus runs under `PortedM68kOooSpec`. To run a subset, point it at
a directory of `.s` files:

```sh
PORTED_TEST_DIR=/path/to/asm sbt "testOnly *PortedM68kOooSpec*"
```

## Layout

```
src/main/scala/m68k040/
  frontend/    fetch, align, predecode, branch prediction
  decode/      decoder, microcode engine, µop assembler
  rename/      RAT, freelists
  dispatch/    dispatch into the ROB and issue queues
  rob/         reorder buffer, commit, precise exception entry
  execute/     ALU / branch / divide / FPU / load-store EUs, register files
  ls/          store queue, load queue, byte-lane handling
  cache/       L1 instruction and data caches
  mmu/         ITLB, DTLB, hardware table walker, MMU control
  exception/   exception unit, stack frames, vector dispatch
  debug/       debug CSRs, halt lanes, trace rings
  top/         top levels (synthesis, socket, simulation)
  isa/ types/ services/   architectural constants, bundles, service interfaces
docs/          design specs, bug write-ups, known deviations
tools/         Musashi reference build, worktree pool, gates
```

## Verification approach

Three independent layers, because each catches what the others miss:

1. **Unit specs** (`src/test/scala`) — per-plugin behaviour, 274 specs.
2. **Ported program corpus** (`src/test/resources/m68kooo-ported-tests/asm`) — 921
   real 68k programs run to a sentinel, many derived from live hardware failures.
3. **Musashi lock-step** — instruction-by-instruction comparison against a vendored
   reference interpreter (`tools/musashi`).

⚠ The reference is not always right. Several corpus tests encoded *Musashi bugs* and
had to be re-pointed at the architecture — for example FMOVEM.L control-register
ordering, where Musashi's own push/pop pair does not round-trip and the Q700 FPSP's
mirrored `-(A7)`/`(A7)+` save/restore proves the correct layout. When a test and the
core disagree, check the PRM and the ROM before assuming the core is wrong.

## Known gaps

Tracked in-tree rather than hidden:

- `docs/BUG_*.md` — open defects with reproductions.
- `docs/KNOWN_DEVIATION_*.md` — deliberate, documented divergences from the 68040.
- FSAVE's displaced/absolute EA forms (`(d16,An)`, `(xxx).W/.L`) are not implemented
  and take the vector-11 trap; the register-indirect forms the FPSP uses are.

## Design documents

- Architecture: `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`
- ISA completion / microcode: `docs/superpowers/specs/2026-06-12-isa-completion-microcode-architecture-design.md`
- Plans and campaign notes: `docs/superpowers/`
