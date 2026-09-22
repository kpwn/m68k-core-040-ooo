# Precomputed IQ hot predicates

Preserve the compacting IQ and every issue/wakeup cycle. At insertion,
precompute the three predicates consumed repeatedly from each stored hot record:

- `isLsClass = cluster == LS && (memOp != NONE || leaAddr)`.
- `isCplxClass = cluster == CPLX`.
- `srcBRead = psrcBValid && (!useImm || isLsClass || op in
  {PACK, UNPK, BITFIELD, BFRESOLVE})`.

The exact LS/LEA rule and all immediate-operand exceptions are retained.
Use these bits in the existing helper functions; no decode-stage change,
new pipeline stage, queue policy, scoreboard ownership or service/Global key.
The wide cold payload remains intact and supplies the execution units.

Raw fields remain in the plain `IqHot` bundle for simulation observability and
an independent per-slot predicate check. They no longer feed functional IQ
logic and should be pruned by synthesis, like its existing debug-only `op`.
The anticipated gain is less replicated classification logic and fewer live
payload bits on the queue-wide compaction enable and five issue muxes. Netlist
area/routing must measure that claim; adding source fields is not evidence of
either a physical gain or a loss.

Validation uses the actual production helper functions on registered
`IqHot.assignFrom` results, covering every declared opcode, cluster and memory
operation and all combinations of leaAddr/useImm/psrcBValid. The original
formulas are checked independently, including inconsistent-but-representable
class/op combinations. Simulation-only assertions compare the stored flags
with the raw fields for each occupied slot on every cycle. Preserve the
parent's seeded IQ cycle trace and all sixteen matched IPC rows, run focused
IQ tests and test-fast, and prove that an intentionally omitted PACK exception
fails. Full-SoC 200 MHz route remains the physical acceptance gate.

Validation on 2026-09-22: all 26 focused tests pass before/after, including
3648 registered predicate cases across 38 opcodes and the unchanged 2492-cycle
seeded scheduling trace. Removing PACK from the actual RTL exceptions fails
the independent check for an immediate operand with a valid B register.
Restored RTL passes again. Required fast gate: 396 passed, two ignored.
All sixteen matched BoardStringCopy IPC rows equal the stationary-position
parent. Production generation and source hashes pass; per-slot simulation
checks are absent from the generated production RTL. Physical savings and
200 MHz timing remain unverified.
