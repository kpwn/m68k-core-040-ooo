# Documentation map

Start with the [core README](../README.md) for build commands and board status.

- [Architecture and invariants](superpowers/specs/2026-05-31-m68k-040-ooo-architecture-design.md)
- [ISA completion and microcode](superpowers/specs/2026-06-12-isa-completion-microcode-architecture-design.md)
- [Debug trace taps](debug-trace-taps.md)
- [IPC experiment ledger](ipc-experiments.md)
- [IPC at 200 MHz: fifteen design alternatives](ipc-design-options.md)
- [Memory-dependency design and integration status](memory-dependencies.md)
- [Synthesis tooling](../synth/README.md)
- [Third-party notices](../THIRD_PARTY_NOTICES.md)
- [Repository cleanup and historical recovery](repository_cleanup.md)

The dated specs, plans and campaigns under superpowers/ preserve design
rationale and investigation history. They are not all descriptions of current
RTL. Likewise, BUG_*.md records discoveries, including subsequently fixed bugs;
consult the current implementation and regression tests before treating a
historical observation as an open defect. KNOWN_DEVIATION_*.md describes
intentional architectural differences.

Design documents, minimal reproductions and regression corpus files remain
versioned. Raw simulator output, waveforms, generated RTL, FPGA checkpoints
and experiment launchers tied to a private scratch checkout do not belong in
the published tree.
