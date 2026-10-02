# Format-7 pending-write and multi-access restart gap

Status: implementation decision pending. This audit does not authorize a different
ISA or select a hidden micro-op-resume mechanism. Existing architecture remains
binding until a complete implementation amendment is reviewed.

## Evidence and immediate diagnostic

At integrated source `0f1e4571`, `ExceptionUnit.frameWordData` writes only
SR/PC/format, EA, SSW and FA. WB1/2/3 status/address/data and push-data words are
zero. `LsFault` carries identity, address, direction, size, privilege and ATC
classification, but no pending-write data. `R_FMTWAIT` accepts format 7 and goes
directly to `R_REDIR`; it does not restore continuation state from SSW/EA.

The primary reference is the local MC68040 User's Manual,
`/home/qwertyoruiop/MC68040UM.txt`, sections 8.4.6.3–8.4.6.7. A normal physical
write error requires a valid WB1 descriptor with the original fault address and
memory-aligned write data. The handler completes pending writes; RTE is not a
general invisible micro-op continuation. Format-7 RTE does have defined MOVEM
and pending-exception continuation behavior. A resumed MOVEM can repeat earlier
operand accesses. Preserving all earlier device reads exactly once is therefore
not a blanket ISA requirement to invent.

`docs/superpowers/diagnostics/format7_normal_write_frame.s` isolates the
unambiguous normal-write case: caches/MMU off, one aligned long store to an unmapped
physical address, then check frame format/vector, FA, WB1S.V, WB1A and WB1D. It stops
in the handler and makes no assumptions about saved-PC or address-register
restart state. This diagnostic stays outside the default ported corpus while
its required behavior is unimplemented. A red result must remain visible in
production-readiness tracking.

The diagnostic ran on `0f1e4571` with shipping harness, LS-OoO OFF and all
experimental CPU environment overrides cleared. It fails at `0xBADF0004`
(missing WB1 valid) after the format/vector and FA comparisons passed:
`/tmp/codex-format7-normalwrite-diagnostic.log`. One test ran; none were skipped.
The later WB1 address/data comparisons were not reached. Their missing RTL
assignments are separate source evidence, not a claimed dynamic check.

Reproduce from this checkout using the normal serialized heavy-job wrapper:
`FUZZ_SHIPPING=1 FUZZ_LS_OOO=0 PORTED_TEST_DIR=docs/superpowers/diagnostics`
and `sbt 'testOnly m68k040.fuzz.PortedM68kOooSpec -- -z format7_normal_write_frame'`.
Use a clean CPU/LS override environment; this is expected to fail until the
pending-write implementation lands.

| Frame field | Byte offset | Diagnostic requirement |
| --- | --- | --- |
| Format/vector | 6 | 0x7008 |
| WB1 status | 0x12 | Low-byte bit 7 set |
| Fault address | 0x14 | 0xAAAA0000 |
| WB1 address | 0x28 | 0xAAAA0000 |
| WB1 data | 0x2C | 0x12345678 (aligned long) |

The historical `exc_partial_macro_move_mem_mem` remains failure evidence, but
its handler changes live A1 and assumes a generic mid-instruction store resume.
It is insufficient as an architectural oracle. Do not make it green by silently
inventing that resume mechanism or dropping the preceding source access.

## Required implementation decisions

1. Classify read, normal physical write, translation, cache-push and MOVE16 faults
   separately. Resolve the manual's differing MOVE16 WB1-valid wording before
   selecting those fields. Define original logical FA versus bus beat address,
   data alignment and partial write strobes for each case.
2. Capture pending-write payload before SQ/cache state is popped or overwritten.
   Prefer a bounded exception-owned snapshot if precise retirement guarantees
   only one owner; prove that bound before using it. Do not automatically widen
   every ROB entry by full data/address records. Specify ownership across flush,
   nesting, late B responses and exception-entry faults using public services.
3. Define macro architectural register/CCR effects and stacked-PC choice for each
   fault class. Atomic register rollback alone cannot undo memory/device effects;
   translation preflight cannot prevent a later physical bus fault. Address-update
   tail micro-ops after a faulting store need an explicit retirement contract.
4. Implement format-7 continuation from frame fields, including MOVEM EA and
   pending trace/FPU paths, while respecting handler-modified PC/SR and nested
   frames. Do not rely on hidden state surviving an arbitrary handler or RTE.
5. Cover MOVE memory-to-memory, MOVEM, CMPM, memory BCD, CAS2, bitfields, MOVE16
   and serialized exception transfers. Keep descriptor U/M errors consistent
   with the separately selected metadata-ordering contract.

## Acceptance before production closure

Inject translation and physical read/write errors at every relevant subaccess;
check frame bytes, handler-visible registers/CCR, memory effects and resumed
execution independently. Include byte/word/long and crossing accesses, inhibited
and copyback modes, delayed/reordered responses, repeated and nested faults,
handler-modified stacked PC and both ordinary and MOVEM continuation.

Use side-effect-counting device models where the ISA requires avoiding a repeated
access. Tests must also allow architecturally specified repetition rather than
forcing a stronger invented contract. Preserve normal no-fault overlap and report
matched IPC plus raw state cost; routed area/timing remains a separate gate.

This work is not closed by a correct frame length, a matching vector alone, or
passing an adjusted version of the old handler. The complete frame/retirement/RTE
contract and fault-injection evidence are required.


## Split-store FA correction checkpoint (2026-10-01)

A separate, unambiguous part of the frame contract is now corrected on branch
`design/format7-restart`: spec amendment `50549e1b`, RTL and fault oracle
`2a5df8c1`, and retirement-witness fixture correction `9deaee35`. Ordinary
precise SQ errors now retain the original transfer's first-byte logical VA on
both halves, per M68040 UM §8.4.6.4. The directed case crosses a logical page
boundary and maps its halves to noncontiguous physical pages; A succeeds and B
faults, with original VA, owner, size, write/supervisor/ATC attributes and terminal
completion checked. No new state or interfaces were added; a phase-select mux
was removed. Mapped area and timing have not been measured.

Frozen source `9deaee35` passed seven P2.4 store-queue tests, three randomized
payload-ownership tests, and the required default `test-fast` (404 passed).
Logs: `/tmp/codex-split-fa-v2-focused.log`,
`/tmp/codex-split-fa-v2-fast.log`; manifest
`/tmp/codex-split-fa-v2-results.json`.
The first broader focused run exposed a stale IRQ fixture expecting release
one cycle after B. The current launch-to-retirement protection deliberately
lasts until the owning ROB head advances; the revised fixture verifies four
held-head cycles and immediate release on head advance, preserving that RTL.
The original failed run is retained in `/tmp/codex-split-fa-focused.log`.

This fixes FA only. Missing WB1 contents, pending architectural effects and
ordinary/special instruction continuation remain open. Combined integration
validation is separate from the branch-local evidence above.
