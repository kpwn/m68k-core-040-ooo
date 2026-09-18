# Immediate-source FPU emulation fault

## 2026-09-19: unimplemented-frame E1 correction

After dynamic FMOVEM admission and static-list ordering repairs, the real ROM
test completes but yields 4 rather than 8. `/tmp/codex-fmovem-rom-numerical-trace.log`
shows the handler entering 4088e244 (packed-source processing) from 4088e194,
then executing decimal conversion on the binary source operand.

The prior frame contract set E1 unconditionally. This conflicts with Motorola's
actual FPSP `get_op.S`, `not_fmovecr`: in the unimplemented-instruction path E1
distinguishes a packed source. The Q700 ROM contains the same test. WinUAE's
unimplemented-instruction frame producer independently sets E1 only for packed
operands. The manual table's "Always 1" entry is inconsistent with these paths;
do not apply that entry indiscriminately to vector-11 frames.

Frame contract correction: for the pending unimplemented-instruction state,
E1 is set only for opclass 010 with packed format 011. Non-packed immediates and
register sources clear it. This does not specify arithmetic-exception frames.
The directed FSCALE handler must assert that E1 is clear before consuming the
saved binary operands. Real-ROM numerical/stack/count checks remain mandatory.

Primary implementation sources:
- https://raw.githubusercontent.com/torvalds/linux/master/arch/m68k/fpsp040/get_op.S
- https://raw.githubusercontent.com/tonioni/WinUAE/master/fpp.cpp

After the E1 correction, both `FpImmediateEmulationSpec` tests PASS:
`/tmp/codex-fscale-e1-packed-fix.log`, 2026-09-19 01:15:55. This includes the
unchanged ROM FPSP numerical result (FSCALE followed by FDIV yields 8), restored
SP, exactly one vector-11 entry, and the directed handler's 20 iterations with
an explicit non-packed E1-clear assertion. No ROM patch or pass bypass was used.
Frame discriminator unit checks and the post-edit fast gate are running next;
this is not yet a build/board validation claim.

`/tmp/codex-fsave-e1-discriminator.log`: the new packed/binary discriminator
matrix and pending-frame layout tests both pass. The broad name filter also
selected the old consumption test, which fails on its 4-byte second-FSAVE
expectation versus the pre-existing deliberately fixed-52-byte implementation.
That contract mismatch is not repaired or hidden by the E1 change. The complete
selected invocation therefore exits 1 (2 passed, 1 failed). Fast gate launched
separately in `/tmp/codex-fmovem-fscale-final-fast.log`.

Post-edit fast gate PASS, 2026-09-19 01:19:47: 372 tests, zero failures,
two ignored, 273 suites. Command:
`make SBT='/home/qwertyoruiop/sbt/bin/sbt -J-Xmx6g -J-Xss16m' test-fast`.
This gate does not resolve the separate idle-frame contract mismatch or replace
the full-core Verilator regressions and board validation.

Validation preceding this correction: 16 expanded FMOVEM tests passed in
`/tmp/codex-fmovem-auto-and-static-corrected.log`, including independently checked
static/dynamic predecrement images and postincrement restoration. The separate
flush/branch regressions passed; three old decode oracles were corrected to
use legal list encodings and ascending control-mode register order.

## Evidence

Board image SoC933d613d / CPUbba51970,100MHz. The user observed MacsBug
"Unimplemented Instruction at408EEED4" while running Speedometer. No reset
was performed for this investigation.

Read-only JTAG ROM dump (`/tmp/codex-fdiv-408eeed4.log`):

-408EEECE: F23C 5926 0001 = FSCALE.B #1,FP2.
-408EEED4: F200 0520 = FDIV FP1,FP2.
-408EEED8: F200 0800 = FMOVE FP2,FP0.

Screenshot `/tmp/codex-fdiv-screen.jpg` confirms the reported PC/opcode.
The exception history was overwritten by MacsBug A-line activity. Vector11
halt is armed (lane0 mask0000081c); subsequent read-only polls had not caught
another exception. The original architectural frame has not been captured.

## Reproduced defect

`FpAssembleSpec` test "ROM FSCALE immediate before FDIV requires an FPU
emulation frame" uses the exact instructions and addresses. FDIV passes the
native-execution checks. FSCALE sets faulted/vector11/faultUsesNextPc and
length3, but fpuSoftwareComplete is false. Repro log:
`/tmp/codex-fscale-immediate-repro.log` (one test, expected failure at flag).

MicroOpAssembler's fpuGenRegUnimpl only accepts opclass000/length2, excluding
the immediate opclass010 form. ExceptionUnit selects format2 and captures
FSAVE/FPIAR state only if entryFpuUnimp is true. Thus this instruction takes
format0 while stacking408EEED4, consistent with MacsBug identifying FDIV.
This is a proven RTL defect and a strong board explanation, not a captured
proof that this specific board event followed that path.

## Architectural requirement

MC68040UM9.6.1, printed9-21/9-22:
https://www.nxp.com/docs/en/reference-manual/MC68040UM.pdf#page=265

Recognized unsupported FP instructions use vector11, format2, next PC, and
real saved FPU emulation state. Generic illegal F-line instructions use
format0 instead. The format2 additional address for this FP case is the
calculated effective address, NOT universally the faulting instruction PC.
FPIAR identifies the faulting FP instruction. Do not repair this by changing
only the saved PC, implementing native FSCALE outside the design, or setting
only the frame flag.

## Further confirmed integration gaps

In board revision bba51970, ExceptionUnit.committedFpSrcIn and committedFpDstIn default to zero. A source
search finds no production assignments overriding them. Simply broadening
the flag would feed zero operands to the emulation handler. For opclass010,
extension bits12:10 describe an operand format, not a source FP register.
The existing narrow test explicitly excludes immediate-source frame capture
to avoid this misuse; that test is a historical scope constraint, not proof
of full architectural support.

Required repair/verification:

1. Deliver the correct frame classification/command and nextPC for recognized
   unsupported forms, preserving generic illegal-F-line behavior.
2. Capture the actual converted source operand and precise FP destination
   operand, through services and without speculative-state leakage. Immediate
   source+1 must not be interpreted as FP6 or replaced by zero.
3. Supply the FP effective address/FPIAR semantics and correct FSAVE fields.
4. Test nonzero, distinct source/destination data and handler emulation through
   FSAVE/RTE; assert the resulting FP2 and subsequent FDIV result, not just
   frame headers. Test flush/rename pressure and source-format variants.
5. Re-run CPU fast gate and serialized focused/full-core regressions before
   integrating into the build tree.

The ongoing200MHz build uses unchanged CPUbba51970 and does NOT contain this
repair. Keep its timing experiment separate from future FPU correctness work.

## In-progress repair (not integrated)

Added CommittedFpMapService, produced only by RenameStage. RobPlugin allocates
two non-bypassed PRF read ports through FpRegFileService and feeds committed
source/destination values into its exception unit. The source read is gated to
R/M=0. A separate FpTrapImmediateService (sole producer DecodeStage) exposes a
read-only port on the existing immediate table. Unsupported non-packed
immediate forms allocate that table and carry its tag into the ROB; they remain
backend-owned until exception capture, then ordinary squash frees the tag.
The ROB converts integer/single/double source bits to extended precision and
passes extended sources through. Exception capture asserts source validity.
The immediate form now sets the emulation flag and correct command word.
Its format2 EA is instructionPC+4; FPIAR remains instructionPC.

Validation so far:

- Full socket elaboration passed for operand-service plumbing and immediate path.
- Exact-ROM decoder regression now passes:
  `/tmp/codex-fscale-immediate-fixed-decode.log`.
- Full-core regression first failed at the format2 EA check (dead0003), proving
  the original frame carried the instruction address rather than literal EA.
- With EA corrected, `FpImmediateEmulationSpec` passed20 iterations through
  real decode/rename/ROB/exception/FSAVE/RTE. It checks format202c, nextPC,
  EA, FPIAR, CMD5926, source1.0 and destination12.0, reloads the saved destination,
  doubles it in a test handler, then verifies native FDIV by3 yields8.
  `/tmp/codex-fscale-fullcore-ea-fix.log`.
- This is a directed protocol handler, NOT the ROM FPSP kernel. Wider source
  format/flush/rename coverage, real-kernel compatibility, and post-edit gates
  remain required. Memory-source capture and packed-source behavior remain
  separate unresolved gaps; no board programming has occurred.

Real-ROM follow-up added: reuse the existing verbatim
0x4088d000 ROM fragment and invoke its 0x4088d9fe FPSP entry for the exact
FSCALE.B #1,FP2 / FDIV FP1,FP2 pair. The test requires result8, restored SP,
exactly one vector11 entry, and no chaining or unexpected exception. Its
assembly was independently assembled/disassembled successfully. It disables
the runner's optional BKPT-as-pass shortcut, so only the checked numerical
completion sentinel can pass.

Real-ROM result: FAIL dead1022 (second vector11 entry), reproduced twice in
`/tmp/codex-fscale-real-rom.log` and `/tmp/codex-fscale-real-rom-exc.log`.
The second exception is NOT a retry of the original FSCALE: its stacked nextPC
is4088e26a, with faulting instruction at4088e264. Disassembling the unchanged
embedded ROM gives `F22E F800 FF28 = FMOVEM.X D0,(-216,A6)`, a dynamic
register-list store used by the FPSP itself. DecodeStage explicitly excludes
extension bit11=1 in both FMOVEM.X slot classifiers. The old design and
`fpu_fmovem_x_an_dynlist_fline.s` deliberately pin this as an unsupported gap.
Thus frame/operand repair alone is insufficient for actual ROM completion;
dynamic-list FMOVEM support needs a design update and dedicated verification,
not a ROM patch or allowing nested traps to count as success. This result is
simulation evidence, not a claim that this second exception was seen on board.

Clean-checkout baseline fast gate:366 passed,6 failed,2 ignored. Failures are in
FrameworkSmokeSpec, RobFaultArbitrationSpec, FpCommitPathSpec, ConfigSpec(two),
and RobPluginSpec. This run predates the operand-plumbing edits. The build
checkout's earlier372-pass result includes local test changes absent here;
neither result validates the new plumbing. Log: /tmp/codex-fscale-test-fast.log.

After integrating Scc commits58a5e831/d36c3b7a/465371f2 into this isolated
worktree, the combined post-edit fast gate passed372tests,0failed,2ignored,
265suites. `/tmp/codex-fpu-scc-integrated-fast.log`, session33446exit0,
2026-09-19 00:04. The real-ROM FSCALE compatibility test remains failing on
dynamic-list FMOVEM and is not covered by this fast-gate success.
