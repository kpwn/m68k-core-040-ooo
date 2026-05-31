# m68k-core-040-ooo

Out-of-order, superscalar 68000–68040 core. SpinalHDL, plugin (Fiber+Database) backbone.

- **Design doc:** `docs/superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md`
- **Plans:** `docs/superpowers/plans/`

## Commands
    make compile      # sbt compile
    make test-fast    # fast scalatest gate (excludes slow/verilator/board tags)
    make test         # full suite
    make verilog      # elaborate generated/M68kCore.v

Note: sbt is not on PATH in this environment; invoke as `make SBT=~/sbt/bin/sbt <target>`.

## Layout
    src/main/scala/m68k040/core       top shell + framework plugins
    src/main/scala/m68k040/isa        architectural constants + enums
    src/main/scala/m68k040/types      keystone bundles (MicroOp, RobEntry, ...)
    src/main/scala/m68k040/services   cross-plugin service interfaces
    src/main/scala/m68k040            M68kParams, Global key registry
    tools                             worktree pool, pm gate, musashi
