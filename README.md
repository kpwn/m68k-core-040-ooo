=== BEGIN HUMAN ===

This is a vibecoded core that tries to faithfully implement a high-performance 68040 core in SpinalHDL. 

There is a Macintosh Quadra 700-ish SoC sister repo at: https://github.com/kpwn/macqd700-soc

I'm using this as a benchmark for LLM model capabilities in the face of large complex projects, and this was in my bucket list
of things to do within my lifetime for a long while now. 

I'll probably still end up rewriting this manually at some point just for the fun 
of it, but yeah - i went into this not expecting a LLM to actually succeed.

The core is heavily inspired by NaxRiscv by Dolu1990 and his copyright (MIT, (c) Charles Papon) applies on this project due to being the reference core.

= https://github.com/SpinalHDL/NaxRiscv

This core likely has many flaws and bugs; I know of several that are triggerable by running common Macintosh applications, mostly around FPU. (EDIT: known ones have mostly been fixed in recent changes; CPU probably still has plenty of non-known flaws :) )

In recent benchmarks we achieved roughly 0.3 IPC while running Dhrystone. Our branch prediction had a roughly 25% mispredict rate under that benchmark: not great, but at 200MHz it's roughly 5 times a real 25MHz 68040. Ultimate goal is to get >1IPC in Dhrystone, bringing us to a core 50% faster than the real thing clock-per-clock. Specific microbenchmarks show our IPC is at most ~1.9, consistent with the 2-wide retire we have going on.

It's stable and correct enough to boot System 7.0.1 and System 7.5.3; A/UX, Amiga etc. hasn't been tried yet but I'd assume this has applications in that space.

The core does NOT model a real 68040 from the bus perspective: instead AXI is used to allow for multiple OoO memory transactions in-flight.
The goal was to have a core that from the programmer's perspective is the real thing - NOT a drop-in replacement from the bus perspective.

 - 2k26 KJC *still* out here

=== END HUMAN ===

=== BEGIN LLM ===

# m68k-core-040-ooo

An out-of-order, superscalar Motorola 68040 CPU core written in
[SpinalHDL](https://github.com/SpinalHDL/SpinalHDL), built on a plugin
(Fiber + Database) backbone.

It is a real core, not a model: it boots Mac OS to the Finder on FPGA, inside a
Quadra 700 SoC, executing the stock Q700 ROM unmodified (*except for timing issues needing a patch due to having too fast of a core relative to wall clock).

## Status

| | |
|---|---|
| Target | MC68040 user + supervisor, FPU, MMU |
| Language | SpinalHDL (Scala), elaborated to Verilog |
| Scale | 117 main Scala source files (2026-09-19 snapshot) |
| Verification | ScalaTest unit/integration suites · 933 ported assembly programs · Musashi lock-step |
| Silicon | Xilinx KU5P; board-tested **200 MHz** Quadra 700 SoC image |
| Boots | Mac OS to the Finder on the stock Quadra 700 ROM |

The core is under active development. Defect investigations and deliberate deviations
are recorded in `docs/BUG_*.md` and `docs/KNOWN_DEVIATION_*.md`; these include historical
reports, not only currently open issues — see
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

### Clocking

The core targets **200 MHz**. The
[2026-09-19 Quadra 700 SoC release](https://github.com/kpwn/macqd700-soc/releases/tag/200mhz-20260919)
runs the CPU at 200 MHz with a 50 MHz peripheral bus. Earlier bring-up images used
100 MHz. Timing closure belongs to a particular routed build and configuration;
it is not a guarantee for every integration or subsequent source revision.

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
make check-publication # reject tracked build debris and firmware images
```

`sbt` may not be on PATH; invoke as `make SBT=~/sbt/bin/sbt <target>`.
The fast gate still needs the simulation toolchain for untagged simulation tests;
it is not a Scala-only check. Full tests / Verilator campaigns must be serialized
with other heavy runs (see AGENTS.md).

`make verilog` generates the minimal framework top, not the integrated CPU.
See [synthesis tooling](synth/README.md) for full-core/socket elaboration.

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

1. **Unit/integration specs** (`src/test/scala`) — per-plugin and full-core behaviour.
2. **Ported program corpus** (`src/test/resources/m68kooo-ported-tests/asm`) — 933
   real 68k programs run to a sentinel, many derived from live hardware failures.
3. **Musashi lock-step** — instruction-by-instruction comparison against an optional
   upstream reference interpreter (`tools/musashi`). Initialize it with
   `git submodule update --init tools/musashi/musashi`, then `make musashi`.
   Builds apply our integration patch to an ignored copy, keeping the submodule clean.

⚠ The reference is not always right. Several corpus tests encoded *Musashi bugs* and
had to be re-pointed at the architecture — for example FMOVEM.L control-register
ordering, where Musashi's own push/pop pair does not round-trip and the Q700 FPSP's
mirrored `-(A7)`/`(A7)+` save/restore proves the correct layout. When a test and the
core disagree, check the PRM and the ROM before assuming the core is wrong.

## Known gaps

Tracked in-tree rather than hidden:

- `docs/BUG_*.md` — defect investigations with reproductions; some describe fixed revisions.
- `docs/KNOWN_DEVIATION_*.md` — deliberate, documented divergences from the 68040.

Historical reports must be checked against the current RTL and regression tests.
For example, the older FSAVE/FRESTORE computed-EA limitation is no longer current:
those forms now use the shared EA-computation path.

## Design documents

- Architecture: `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`
- ISA completion / microcode: `docs/superpowers/specs/2026-06-12-isa-completion-microcode-architecture-design.md`
- Plans and campaign notes: `docs/superpowers/`

See [the documentation index](docs/README.md) for current entry points and
[the cleanup record](docs/repository_cleanup.md) for removed historical artifacts.
Original contributions are MIT; [third-party notices](THIRD_PARTY_NOTICES.md)
describe the dependencies and exceptions.
