# Dynamic FMOVEM.X: commit-serialized backend path

Status: implemented in the isolated `codex-fpu-immediate/agent-01` worktree;
directed integration checks pass, broader verification and board validation remain.

Latest checkpoint (2026-09-19 01:16): dynamic admission no longer depends on
the unused DecodePacket.valid payload field. Six independent full-core FMOVEM
image/auto-address tests and ten corrected static decode tests pass (16 total,
`/tmp/codex-fmovem-auto-and-static-corrected.log`). The unchanged ROM FPSP FSCALE
test now passes after correcting the separate FSAVE E1 packed-source flag defect;
see `docs/FSCALE_IMMEDIATE_BOARD_FAULT.md`. The post-edit fast gate passed
372 tests, zero failures, two ignored (01:19:47,
`/tmp/codex-fmovem-fscale-final-fast.log`).
Full-mask CPU/rename-pressure, restart/fault coverage and a new bitstream remain
outstanding. The following checkpoints are chronological implementation history,
not a claim that earlier missing pieces remain absent.

01:24 checkpoint: full-core dynamic predecrement store/postincrement load passes
all 256 masks with high-bit noise in D0, distinct FP values, independently checked
memory images, A7 restoration, and unselected-register checks:
`/tmp/codex-fmovem-all-mask-core.log` (242 seconds, one exhaustive test).
The next version rotates the mask source through D0..D7 and adds frame-boundary
guard words; it is running in `/tmp/codex-fmovem-all-mask-registers-guards.log`.
This does not establish translated/fault-restart or all EA coverage.

01:29 checkpoint: the strengthened 256-mask run PASSes with rotating D0..D7
mask sources and guard words (`/tmp/codex-fmovem-all-mask-registers-guards.log`,
253 seconds). Current full socket generation PASSes in
`/tmp/codex-fpu-cold-current-socket.log`; generated files are isolated under
`/tmp/codex-fpu-cold-current-socket`, preserving the completed build's inputs.
Top-level SoC lint against this exact Verilog PASSes (exit 0) in
`/tmp/codex-fpu-cold-current-top-lint.log`. The targeted real-MMU/copyback full-core
test is now running separately; no new bitstream has been built or loaded.

01:31 checkpoint: real-MMU/copyback full-core test PASS
(`/tmp/codex-fmovem-mmu-core.log`), with nonzero I/D table walks, descriptor
updates and cache hits required by assertions. The historical dynamic-F-line
corpus fixture now checks actual D0 load/D3 store data and retains an illegal
postincrement-store trap check. Both selected corpus cases PASS in
`/tmp/codex-fmovem-corpus-admission.log`. Nonidentity mappings, exhaustive fault
restart and broader EA/younger-pressure verification remain outstanding; a
new 200 MHz build is a diagnostic candidate, not a completion claim.

## Implementation checkpoint (2026-09-19)

The standalone backend walker, byte-transfer unit, and composed engine exist.
Their three Verilator tests passed in the reserved cold-path worktree; these
tests do not establish CPU integration or architectural restart correctness.
The components have been copied into the integrated FPU/Scc worktree.

`SerializedMemoryContextService` has one producer, RobPlugin, and supplies the
ordinary instruction's privilege and cache attributes to the shared backend
memory port. It remains inactive until the instruction adapter is implemented.
Initial service wiring passed compilation and full socket elaboration
(`/tmp/codex-fmovem-context-socket.log`). Subsequent command-capture changes
retain the translated cache mode while enforcing CACR.DE on loads and stores;
post-change full socket elaboration also passed
(`/tmp/codex-fmovem-context-socket-de.log`). The initial fast-gate invocation
exhausted the default Java heap while compiling tests, before tests ran. Its
6 GB heap retry passed 372 tests, zero failures, two ignored, across 268 suites
(`/tmp/codex-fmovem-context-fast-6g.log`). This gate predates the composed backend
wrapper below. Active CPU-context simulation remains required; inactive-path
elaboration does not verify the new memory behavior.

Still missing: CPU instruction admission/capture, translated adapter integration,
safe architectural FP/An writes, defined fault/restart behavior, and the real-ROM
FPSP regression passing. None of these changes is in the running 200 MHz build.

The standalone `FmovemColdMemory` translated-byte adapter now passes 576
deterministic cases (`FmovemColdMemorySpec`, seed 68043,
`/tmp/codex-fmovem-cold-memory.log`). Coverage includes nonidentity 4 KiB/8 KiB
mapping with odd PPNs, virtual boundary addresses, captured privilege and DE,
all three cache policies, load/store, unrelated translation tokens, backpressure,
and translation versus precise-memory faults. It does not issue memory on a
translation fault. The CPU connection to this adapter remains outstanding.

`FmovemColdBackend` composes the transfer engine and translated-byte adapter,
capturing privilege, page size, and cache enable once per instruction. It retains
translation-versus-memory fault provenance alongside the exact failing byte VA.
The commit owner still must supply precise, drained memory ports and safe FP
staging/writeback. Its page-crossing/fault regression passed (seed 68044,
`/tmp/codex-fmovem-cold-backend.log`): two-register store spanning a nonidentity
page mapping, success, translation failure, and memory failure on byte 17;
instruction context survives changed live inputs, and no transaction follows a
fault. This is not yet a CPU instruction test.

The CPU owner path is now being integrated, with decode admission still disabled.
`SysKind.FMOVEM_DATA` is appended (existing ordinals unchanged). Its captured
metadata is `sysVal=EA`, retired `sysAux(0)=Dn mask`, and `sysRc[6]=store`,
`sysRc[5:3]=EA mode`, `sysRc[2:0]=An`. The eventual final uop must have no renamed
integer or FP destination. RobPlugin allocates cold PRF ports through the existing
register-file services and resolves destinations through committed-map services.

ExceptionUnit enters the backend only after S_DRAIN, stages completed load values,
and emits committed FP/An writes only after successful completion. An/A7 are not
precommitted at trigger. On a fault it drops staged loads and enters the existing
format-7 machinery with instruction PC, exact byte VA, and byte-sized SSW carrying
the actual R/W, privilege, and ATC classification. Already acknowledged stores are
not rolled back. This is the proposed retry-from-scratch policy; architectural
fault/restart conformance and register-port lifetime must still be verified before
instruction admission. Do not treat successful elaboration as that proof.

The integration compile/elaboration log is `/tmp/codex-fmovem-owner-compile.log`.
The previously passing 372-test gate predates these owner-path edits.

`FmovemDataApplySpec` now compiles and is queued after the owner fast gate
(`/tmp/codex-fmovem-owner-fast.log`, test log
`/tmp/codex-fmovem-owner-apply.log`). It directly exercises ExceptionUnit, not
decode or the real renamed PRFs: drain barrier, two-register predecrement store
and postincrement load, exact byte VA/SSW on translation and memory errors,
no early FP/An writes, and success-path A7 bank convergence. The test uses ordinary
top-level ports; it does not reach into another plugin's internal state.
An additional ROB assertion forbids a renamed destination on the final cold uop.

Owner validation update: the first owner fast gate had 371 passes and one failure.
`DebugIntRfStubPlugin` exposed only `writes.head`; adding the cold An port meant the
debug test watched the wrong writer. The stub/test now observe every allocated
writer, preserving the exact expected-write assertion. The first direct owner
run stopped before simulation because Verilator reserves the output name `vector`;
renaming it `faultVector` fixed the harness without changing RTL behavior.
`FmovemDataApplySpec` (six load/store × success/translation-fault/memory-fault
scenarios) and all six `DebugCtrlRobIntegrationSpec` tests now pass together:
seven ScalaTest tests, zero failures, `/tmp/codex-fmovem-owner-apply-fixed.log`.
These results do not yet cover real renamed PRF pressure or an RTE retry.

Admission integration: the assembler now emits a fixed three-uop sequence
(`T0 := Dn`, retirement capture; `T1 := EA`, no memory access; final nonprivileged
serialized op reading T1). Slot0 and slot1 use the same encoding-only classifier
to bypass the generic microcode trap route. No runtime mask signal feeds decode.
Full socket elaboration passed (`/tmp/codex-fmovem-admitted-compile.log`), and
`FmovemDynamicAssembleSpec` passed all admitted EA/Dn/direction combinations plus
negative forms and the exact ROM displacement. The full ROM FSCALE/FDIV test now
gets past the nested dynamic-list trap and returns, but fails its numeric-result
sentinel `0xdead1020` in `/tmp/codex-fmovem-admission-rom.log`. The custom immediate
emulation test still passes. FPSP compatibility is therefore not yet proven.
Independent static-versus-dynamic memory-order checks are running in
`/tmp/codex-fmovem-memory-order.log`; the existing static walk's opposite ordering
is a suspect, not yet the established cause of this numeric failure.
The repaired owner fast gate passed 372 tests, zero failures, two ignored
(`/tmp/codex-fmovem-owner-fast-fixed.log`); it predates admission changes.

Static-order update: the independent static control-store test failed with
`0xdead2001`, proving the old walk reversed the memory layout. Static transfers
now drain the highest mask bit in both formats, with predecrement starting at
An-12 and decreasing addresses; control/postincrement increase addresses. The
older static design document was corrected against M68000PRM pp.5-85–5-88.
Both independent static/dynamic store tests pass in
`/tmp/codex-fmovem-order-fix-rom.log`. The original dynamic test without explicit
fault handlers timed out; adding handlers changes its instruction placement, so
alignment/slot equivalence remains important and that timeout is not explained.

The ROM test after the static-order correction again traps at 4088e264, stacking
nextPC 4088e26a with format2/vector11. This is not the previous numeric-result
failure. Slot1/stash routing is being traced; `/tmp/codex-fmovem-rom-slot-trace.log`
failed only because packet1.valid lacked simPublic. The field is now observable
and the retry is `/tmp/codex-fmovem-rom-slot-trace2.log`.

That trace exposed a new classifier bug: aligned payloads can carry
`DecodePacket.valid=False` while the enclosing stream/slot is valid. The new
encoding classifier incorrectly depended on packet.valid. It now uses only
encoding/fetch-fault fields, leaving validity to the existing emission owners.
The assembler regression explicitly covers packet.valid=False, and independent
static/dynamic transfers run both with and without NOP padding. Their joint run
with the ROM test is `/tmp/codex-fmovem-valid-fix-rom.log`.

## Independent 200 MHz candidate (completed, not loaded)

The older L1-fix build finished 2026-09-19 00:45:38 local time. CPU bba51970,
SoC 933d613d; it excludes every Scc/FPU change in this worktree. Artifacts are in
`/home/qwertyoruiop/macqd700-soc-worktrees/codex-fetch-guard/build/vivado200_installer_fix`.
Final `timing_summary.rpt`: WNS -0.024 ns, TNS -0.049 ns, four failing setup
endpoints; WHS +0.006 ns, zero hold failures; zero pulse-width failures.
Worst setup path: Dcache tagMem_0 BRAM to dataMem_3 BRAM address bit 8.
`reports/bus_skew_route.rpt`: 246 reported paths, zero violations, minimum slack
+2.083 ns. Bitstream DRC reports zero errors. Existing MIG crosstalk-topology
critical warnings remain; setup constraints are explicitly NOT fully met.

Bitstream SHA256: `e93214539670c607616aefa3478b03e8a8e597b348ad44d6b8eafe1ab9a5bb9f`.
LTX SHA256: `b99495df5f941fc479a671d28fba43d07b837cbb213fe603f7a214d44cf57d4b`.
This satisfies the user's diagnostic setup allowance, not the reliable-boot goal.
The board has not been programmed; user was asked whether to replace the preserved
halted session or wait for the correctness fixes.

## Reason and scope

The real-ROM FSCALE regression reaches `4088e264: f22e f800 ff28`,
`FMOVEM.X D0,(-216,A6)`, and our existing dynamic-list exclusion raises a
second vector11 exception inside FPSP. Logs:
`/tmp/codex-fscale-real-rom.log` and
`/tmp/codex-fscale-real-rom-exc.log`.

The MC68040 supports dynamic lists: MC68040UM, printed10-32, explicitly
specifies the dynamic-list timing increment:
https://www.nxp.com/docs/en/reference-manual/MC68040UM.pdf#page=323
The old static-only restriction was a project implementation limit, not an
ISA restriction. This update supersedes the dynamic-list exclusions in
2026-08-19-fmovem-data-list-design.md for the EA modes already supported by
the static implementation. Do not silently broaden other unsupported EAs.

User constraint,2026-09-18: dynamic-list handling is a cold path. Do not put
runtime-mask evaluation or execution-feedback waiting in decode.

## Architecture

1. Decode identifies the encoding and emits a fixed bounded sequence, independent
   of mask contents. Normal renamed execution obtains the Dn mask and effective
   address. No committed/speculative RAT or PRF read is added to decode, no mask
   popcount participates in decode admission, and no backend reply drives a
   decode sequencer. Static-list FMOVEM retains its existing path.
2. Capture operands at their own in-order retirement, using the existing
   system-value capture mechanism. A final nonprivileged serialized operation
   starts the backend engine only at the ROB head, after the existing precise
   memory drain. Both mask and EA must belong to this operation, not a later
   producer or a stale earlier invocation.
3. The runtime register walk and all memory/FP transfers occur in the backend
   serialized engine, reusing the existing commit-time translation and precise
   memory interfaces. A byte-at-a-time implementation is acceptable initially:
   it naturally handles odd addresses and cache-line/page crossings without
   widening the hot LSU datapath. Never use identity-physical addressing when
   translation is enabled.
4. Obtain FP register data and update FP destinations only through services.
   Committed mappings are usable only after younger speculative work has been
   squashed and its writes drained or otherwise excluded. A dedicated cold
   register-bank access service must document exactly one producer. Do not
   reach into RenameStage or another plugin's implementation.
5. Register selection, address progression, predecrement/postincrement updates,
   and completion live in the cold engine. Only low8bits of Dn are the mask;
   bits6:4 of the extension name Dn and are never themselves a mask. Preserve
   FPSR/FPCC/CCR. Empty mask transfers no bytes and leaves An unchanged, but
   still completes exactly one instruction.
6. Redirect to the instruction's real nextPC only after completion. Do not
   emulate via nested vector11, patch ROM instructions, special-case a PC, or
   substitute a numerical constant for a transferred FP value.

## Integration hazards to resolve before admitting the encoding

- Register ordering is independently specified by M68000PRM, printed5-85
  through5-88 (https://www.nxp.com/docs/en/reference-manual/M68000PRM.pdf).
  Control/postincrement transfers progress FP0toFP7 toward increasing addresses;
  predecrement stores progress FP7toFP0 toward decreasing addresses. Consequently,
  FP0 is at the lowest occupied memory address when all registers are selected.
  The older project design's "highest-numbered register at lowest address"
  statement conflicts with this. Do not copy that statement or use the static
  implementation as the sole oracle. A same-bug store/load round trip can pass.
  The dynamic mask uses the same bit layout as its corresponding static mode.
- Postincrement is load-only and predecrement is store-only in that reference.
  The cold path must reject invalid direction/mode combinations even if the
  existing static classifier happens to admit them. Explicit negative tests are
  required; preserving an implementation bug is not ISA compatibility.
- The existing sysOp path commits a renamed integer destination at trigger,
  before a long memory operation can finish. Do not reuse that for an An update
  without preserving valid architectural An on every fault path. A7 must agree
  with the active stack-bank state, not just an integer PRF value.
- Define access-fault/restart behavior from the architectural manual before
  deciding when loads become architectural and when An changes. Buffering all
  load values is not automatically equivalent to the required restart model.
  Stores already acknowledged cannot be rolled back or falsely reported as
  atomic. Use the existing access-fault machinery with the instructionPC and
  actual failing virtual address; don't convert a fault to normal completion.
- Audit cold-write arbitration and physical-register lifetime through squash,
  interrupt entry, and subsequent rename allocations. A service interface by
  itself does not prove a write is safe.
- Keep serialized instruction metadata widths symbolic. Adding a SysKind must
  not truncate the existing4-bit capture. Avoid extending the microcode ROM
  beyond its9-bit address space without an explicit sizing change; Scc's pending
  repair occupies511of512rows.
- All admitted EA forms must have identical slot0/slot1 behavior. Memory-indirect
  and unsupported full-format forms must retain a defined trap, not execute a
  truncated displacement.

## Required evidence

- All256masks, D0..D7 mask sources, load/store, high-bit noise in Dn, and distinct
  extended payloads in all8FP registers. Check untouched registers and memory
  guards; check ordering against the architecture, not only a round-trip using
  the same implementation in both directions.
- Zero/full/sparse masks, both alignment slots, immediate producer of Dn,
  older long-latency producers, and younger overwrite pressure.
- Existing admitted EA modes, auto-update including A7, odd addresses,
  cache-line/page boundaries, enabled translation, and nonidentity mappings.
- Fault injection at each transfer boundary; precise PC/address, register and
  An state, and restart behavior. Wrong-path/squashed requests must produce no
  memory or architectural FP effect.
- The unchanged ROM FPSP test must complete FSCALE followed by FDIV with result8,
  restored stack, and no nested F-line trap. BKPT is not a pass condition.
- Replace the historical dynamic-trap fixture with real transfer assertions;
  retain illegal-EA/encoding coverage separately. Run existing static FMOVEM
  regressions, CPU fast gate, full socket elaboration, and timing-sensitive
  synthesis checks before integrating a new bitstream.

The ongoing200MHz candidate predates this repair and the FPU frame repair.
Its timing result cannot establish correctness of this design.
