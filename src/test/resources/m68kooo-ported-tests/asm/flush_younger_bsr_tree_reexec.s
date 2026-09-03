| flush_younger_bsr_tree_reexec.s -- the flushed younger call heads a 3-deep
| subroutine TREE, matching the real-hardware boot signature ("an entire
| subroutine tree that MAME executes is silently never executed on cpu040",
| companion SoC repo docs/BUG_calibration_word_misplaced_0d00.md Parts 15/29/62)
| rather than a single lost frame.
|
| Case 1 uses a cold forward Bcc as the older mispredicting branch (its
| fall-through wrong path falls into its own taken target, so the head `bsr` is
| dispatched speculatively and then squashed).
| Case 2 uses a BTB-TRAINED backward loop branch that finally exits: the branch
| is predicted taken and is actually not taken, so `flushPcReg` = its
| fall-through = exactly the `bsr` opcode.
| Case 3 uses an older COLD `bsr` (predicted not-taken) whose fall-through wrong
| path contains a younger `bsr` that IS architecturally executed -- just later,
| after the outer call returns. Here the redirect PC is nowhere near the flushed
| call, so re-execution has to come from ordinary refetch, not from the redirect
| PC happening to point at it.
|
| Each tree entry bumps D5; a full tree is 3 bumps. Expected D5 = 9.
| D6 counts the outer callee in case 3 (expected 1). D4 = wrong-path-only
| fillers, expected 0.

    .text
    .org 0

_start:
    moveq   #0, %d5
    moveq   #0, %d6
    moveq   #0, %d4
    move.l  %sp, %a2

    | ---- case 1: cold forward Bcc, tree head at the redirect PC ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _c1
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_c1:
    bsr.w   _lvl1

    | ---- case 2: trained backward loop exits into the tree head ----
    moveq   #5, %d1
_loop:
    subq.l  #1, %d1
    bne.w   _loop
    bsr.w   _lvl1

    | ---- case 3: older cold bsr; younger bsr on its fall-through wrong path ----
    bsr.w   _outer
    addq.l  #1, %d3
    addq.l  #1, %d3
    bsr.w   _lvl1

    | ---- verify ----
    cmp.l   #9, %d5
    bne     _fail
    cmp.l   #1, %d6
    bne     _fail
    tst.l   %d4
    bne     _fail
    cmp.l   %a2, %sp
    bne     _fail

    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a3)
_halt:
    bra     _halt

_outer:
    addq.l  #1, %d6
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
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a3)
_halt_fail:
    bra     _halt_fail
