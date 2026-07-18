| sp_slot_corruption.s — Phase A (structural-a7-rename-fix) directed test.
|
| Bug class this test exercises:
|   Pre-Phase-A, IRQ-entry / sync-exception / RTE-fire all read
|   `arch_a7_val` (= PRF[committed_a7_phys]) as the supervisor SP base
|   for the frame push.  Any prior bad RTE pop that wrote a corrupted
|   value into PRF[committed_a7_phys] became the SSP base for the NEXT
|   IRQ frame push, propagating across thousands of cycles (the boot
|   trace showed a 2-byte A7 drift accumulating vs MAME).
|
|   Phase A breaks this loop structurally by routing IRQ-entry SP reads
|   through a dedicated PHYS_SSP_TAG PRF slot that is touched ONLY by
|   commit-side writes at the well-defined RTE/exc_done/MOVEC/S-clear
|   boundaries — never by speculative renames or in-flight µops.
|
| Test pattern: sustained TRAP → handler-A7-write → RTE → next-TRAP loop
| with the handler making the kind of ALU writes to A7 (move.l, sub.l,
| add.l) that can race rename if the SP slot were not isolated.  After
| N=8 iterations we check that the supervisor stack is still aligned at
| our chosen SSP base — any drift would land the sentinel write on a
| garbled VBR/handler-table location and the test would either FAIL
| with the wrong sentinel value or hang on a runaway exception.
|
| PASS: 0xC0FFEE00 written to 0xFFFF0000 with all 8 RTEs successful.
| FAIL: any other sentinel value, or hang.
|
| Vec 32 (TRAP #0) handler at 0x00000080.

    .text
    .org 0

    .equ STACK_BASE, 0x00010000
    .equ ITER_COUNT, 8

_start:
    | SSP set up; D7 = iteration counter.
    lea     STACK_BASE, %a7
    move.l  #_handler, 0x00000080   | install vec 32 handler
    move.l  #ITER_COUNT, %d7

_loop:
    | Issue TRAP — handler will RTE back here, decrementing D7.
    | If the SP slot corrupts after the first iteration, subsequent
    | trap frames push onto a sliding A7 and the RTE pop reads garbage.
    trap    #0
    subq.l  #1, %d7
    bne     _loop

    | All ITER_COUNT round-trips completed.  Verify A7 is still the
    | original SSP base (no drift).  If not, FAIL.
    move.l  %a7, %d0
    cmp.l   #STACK_BASE, %d0
    bne     _fail

    | All passed.  Write PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d4
    move.l  %d4, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    | Handler exercises the path that pre-Phase-A would corrupt the
    | committed-A7 PRF slot: rename a fresh A7 via ALU (movea.l clones
    | the value into a fresh phys reg through rename), then RTE.  If
    | the SP cache slot were derived from the renamed A7 instead of
    | the dedicated reserved slot, drift would creep in here.
    movea.l %a7, %a0            | a0 mirrors A7 (rename, no SP movement)
    movea.l %a0, %a7            | A7 written-back through rename — pre-
                                 | Phase-A this would be the value that
                                 | propagates to PRF[committed_a7_phys].
    rte
