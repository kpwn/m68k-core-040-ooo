# Board vector-4 halt at 001DF38C

Captured on CPU `bba5197049e53c82ac7fedccf2f59a937e6d31b7`, SoC
`933d613d`, 100 MHz. Root captures are `/tmp/codex-illegal-1df38c-capture.log`
and `/tmp/codex-new-halt.log`. No board operations were performed by this agent.

## Evidence

- Exception ring: vector 4, PC `001df38c`, handler `0002962a`.
- Last retired PC: `001df38a` (after preceding A-line handlers returned).
- RAM read: `001df38c: 56f6 8161 001c`, followed at `001df392` by `242e`.
- A6 = `000f4516`. Thus the instruction's pointer resides at `000f4532`.
- Monitored ROM fetch probe: control 1, no captured mismatch. That probe does
  not prove the RAM instruction-fetch bytes, but the observed exception is
  exactly what the current RTL intentionally produces for these legal bytes.

A later live capture by the root agent reproduced the same sequence relocated:
vector 4 at `001e567c`, bytes `56f6 8161 001c`, preceded at `001e5678` by
`a746 b1df`. A6 was `000f5756`, A7 `000f5728`, handler `0002d67a`.
Raw stack words `2000001e 567c0010` contain SR 2000, saved PC 001e567c,
and format-0/vector-offset 0010. This recurrence is on the old FPGA image;
the Scc repair was not loaded. It supports a deterministic missing-instruction
path at relocated code, rather than a fault unique to the original RAM address.

`56f6` is SNE with An-indexed destination A6. Full extension `8161` has base
enabled, index suppressed, word base displacement, memory-indirect with null
outer displacement. `001c` is +28. The instruction writes one byte to
`mem32[A6 + 28]`, with value FF iff Z=0, otherwise 00; CCR is unchanged.

## Root cause and repair

DecodeStage's generic memory-indirect admission sees the EA, but its entry
classifier previously lacked Scc. It fell to `MI_UNSUPPORTED_ENTRY`, a
deliberate vector-4 fail-safe. The existing `memind_unrouted_families_trap.s`
even required this Scc trap. This is a missing ISA implementation, separate
from the I-cache slot-reuse defect and FPU-emulation exception frame defect.

The repair extends the existing full-extension microcode architecture with
three appended rows: pointer load into T0, branch-EU Scc condition into T1,
byte store from T1 using the resolved pointer and outer/index displacement.
It reuses the captured opword for the condition and existing EA context for
pre/post indexing. No new state, bundle fields, services, or Global keys.
Existing ROM row addresses remain unchanged. Unsupported memory shifts retain
their illegal-instruction fallback. No destination read is introduced.

## Verification targets

- `SccMemIndirectDecodeSpec`: exact board bytes through actual fetch/decode,
  checking three micro-ops rather than an illegal trap.
- `SccMemIndirectBoardSpec`: full-core execution of all 16 conditions × all
  32 CCR combinations × index-suppressed/preindexed/postindexed forms (1,536
  cases), checking CCR, pointer, register preservation, and neighboring bytes.
- Same full-core suite: pointer-read and destination-write bus faults.
- Updated historical unsupported-family regression: Scc must execute, memory
  shift must still trap.

## Results (2026-09-18)

`/tmp/codex-scc-focused-fixed.log`, process exit 0:

- Exact board-opcode frontend test: PASS.
- All 1,536 condition/CCR/EA combinations: PASS (103 seconds simulation).
- Pointer-read and destination-write bus faults: PASS, including saved PC/CCR.
- Historical unsupported-family regression: PASS (Scc executes, shift traps).
- Full microcode resolver equivalence: PASS, 932 contexts × 511 rows = 476,252
  comparisons, zero mismatches.

The first matrix attempt failed at assembly, not RTL, because generated
branches exceeded word displacement range. Explicit BNE.L assertions fixed
the test generation; the rerun above executed the complete matrix.

Initial required fast gate (`/tmp/codex-scc-fast.log`): 365 passed, 7 failed,
2 ignored, 264 suites. Six failures are unchanged ROB-64 sizing assumptions
in FrameworkSmokeSpec, ConfigSpec, RobFaultArbitrationSpec, FpCommitPathSpec,
and RobPluginSpec. The seventh is RobPluginSpec's debug-PC-apply harness
timeout (seed 728039403): the test drives an internal tied-off signal instead
of a DebugSystemStateService producer. These gate-prerequisite test repairs
are deliberately separate from the Scc product change; that initial gate was NOT green.

Follow-up gate-prerequisite repairs derive widths/wraps from configured params
and drive debug PC apply through a real service input. No simulator seed was
pinned. `/tmp/codex-scc-gate-prerequisites.log`: all 59 affected tests pass,
including debug-PC apply at random seed 1809720275.
`/tmp/codex-scc-fast-fixed.log`: **372 passed, 0 failed, 2 ignored**, 264 suites,
213 seconds, process exit 0. The full fast gate is now green.

Board verification is pending; the running FPGA and active 200 MHz build do
not contain this repair.
