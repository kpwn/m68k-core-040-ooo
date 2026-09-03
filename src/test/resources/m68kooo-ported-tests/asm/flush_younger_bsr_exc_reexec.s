| flush_younger_bsr_exc_reexec.s -- the OTHER flush source: an EXCEPTION-entry
| squash (RobPlugin's `exc.redirectValid` disjunct of `doFlushReg`, not the
| branch-mispredict `branchRedirect` one) must also re-execute a younger
| `bsr`/`jsr` after RTE, not skip it.
|
| Companion to adv_exc_entry_young_mem_squash.s, which already covers younger
| MEMORY ops across an exception-entry squash. This covers younger CALLS,
| because the boot-investigation signature being chased (companion SoC repo
| docs/BUG_calibration_word_misplaced_0d00.md, Parts 29/62) is specifically
| "an entire subroutine tree is silently never executed", and a call that is
| fetched into the shadow of an A-line trap is squashed by the same mechanism.
|
| Case 1: an A-line opword (0xA06E, the ROM's own hot path) with a `bsr`
|         immediately behind it. The handler bumps the stacked PC past the
|         opword and RTEs, so the RTE's redirect PC lands EXACTLY on the `bsr`.
| Case 2: same, but with filler ops between the A-line and the call, so the
|         call is deeper in the squashed window.
| Case 3: same, with a 3-deep call TREE behind the A-line.
|
| D5 counts callee entries (expect 5: 1 + 1 + 3). D7 counts handler entries
| (expect 3). D4 must stay 0 -- it is only touched by the callee-shared
| scratch path, never by squashed work.

    .text
    .org 0

_start:
    move.l  #_handler, 0x00000028   | vector 10 (A-line) @ 0x28
    moveq   #0, %d5
    moveq   #0, %d7
    moveq   #0, %d4
    move.l  %sp, %a2

    | ---- case 1: call immediately behind the A-line opword ----
    .short  0xA06E
    bsr.w   _bump

    | ---- case 2: call 4 ops behind the A-line opword ----
    .short  0xA06E
    addq.l  #1, %d3
    addq.l  #1, %d3
    addq.l  #1, %d3
    addq.l  #1, %d3
    bsr.w   _bump

    | ---- case 3: a 3-deep call TREE behind the A-line opword ----
    .short  0xA06E
    bsr.w   _lvl1

    | ---- verify ----
    cmp.l   #5, %d5
    bne     _fail
    cmp.l   #3, %d7
    bne     _fail
    tst.l   %d4
    bne     _fail
    cmp.l   %a2, %sp
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d6
    move.l  %d6, (%a1)
_halt:
    bra     _halt

_bump:
    addq.l  #1, %d5
    rts

_lvl1:
    addq.l  #1, %d5
    bsr.w   _lvl2
    rts
_lvl2:
    addq.l  #1, %d5
    bsr.w   _lvl3
    rts
_lvl3:
    addq.l  #1, %d5
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d6
    move.l  %d6, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    | Format-0 frame: SR at 0(A7), PC at 2(A7), format/vector at 6(A7).
    | Resume after the A-line opword by bumping the stacked PC by 2, so the
    | RTE's redirect PC is exactly the squashed call's own PC (case 1/3).
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    addq.l  #1, %d7
    rte
