# Design documents (commit `fee95f6f`, the bitstream on the board)

- [00-core-design.md](00-core-design.md) — high-level design: parameters, pipeline, critical loops, cross-cutting models, consolidated findings, corrections.
- [01-frontend-icache.md](01-frontend-icache.md) — L1 I-cache, prefetch, predecode, fetch/align, BTB/FTB/RAS/gshare, FTQ.
- [02-decode.md](02-decode.md) — DecodeStage, OperationDecoder, EaDecoder, decode record and side tables.
- [03-microcode-uops.md](03-microcode-uops.md) — µop format, cracking, microcode ROM, sequencers, µop queue.
- [04-rename-issue-regfile.md](04-rename-issue-regfile.md) — rename, dispatch, issue queue, physical register files.
- [05-rob-exceptions-core.md](05-rob-exceptions-core.md) — plugin framework, ROB/commit, flush, exceptions, system state.
- [06-execution-units.md](06-execution-units.md) — ALU, shifter, branch, CPLX (MUL/DIV/bit-field), FPU.
- [07-load-store-unit.md](07-load-store-unit.md) — LS pipe, store queue, memory ordering, device accesses.
- [08-dcache-axi.md](08-dcache-axi.md) — L1 D-cache, non-blocking MSHRs, maintenance, D-side AXI.
- [09-mmu-socket-debug-top.md](09-mmu-socket-debug-top.md) — MMU/TLBs/walkers, socket glue, debug block, top level.
