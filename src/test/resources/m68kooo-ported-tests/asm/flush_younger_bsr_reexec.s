| flush_younger_bsr_reexec.s -- a CALL squashed as collateral damage by an
| OLDER mispredicting branch must still be RE-EXECUTED after recovery.
|
| Hypothesis under test (from the companion SoC repo's
| docs/BUG_calibration_word_misplaced_0d00.md, Parts 29 and 62): when a branch
| mispredicts, everything younger in the ROB is squashed -- including an already
| dispatched, already EU-resolved `bsr`/`jsr`. If fetch then resumed PAST that
| call instead of re-fetching it, an entire subroutine tree would be silently
| skipped. Part 29 proved by real-hardware ILA capture that the `bsr.w` at ROM
| 0x40800284 is correctly resolved (redirect=1/mispredict=1, robId=18) and then
| collaterally flushed by an OLDER `rts` misprediction (robId=16). Whether the
| flushed call is subsequently re-executed was never directly tested.
|
| Shape of every case below: a COLD forward Bcc (bimodal predicts not-taken)
| that is actually TAKEN, so the fall-through wrong path is fetched and
| dispatched -- and that wrong path falls straight through into the branch's own
| correct target. The call therefore sits in the ROB, younger than the
| mispredicting branch, when the flush fires, and RobPlugin's
| `flushPcReg := nextPcRd0` lands EXACTLY on the call opcode. Filler distance is
| varied (0/3/7/11 two-byte ops) because the call site's offset within the
| 16-byte fetch line changes with it -- k=7 puts a 4-byte `bsr.w` straddling the
| line boundary.
|
| D5 counts callee entries (must end at 7: one per case, two for case 6).
| D4 counts wrong-path-only filler ops (must end at 0: they are architecturally
| dead, so a non-zero D4 means squashed work leaked into committed state).

    .text
    .org 0

_start:
    moveq   #0, %d5
    moveq   #0, %d4
    move.l  #_bump, %a1

    | ---- case 1: bsr.w, distance 0 (target == the very next instruction) ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t1
_t1:
    bsr.w   _bump

    | ---- case 2: bsr.w, distance 3 ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t2
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_t2:
    bsr.w   _bump

    | ---- case 3: bsr.w, distance 7 ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t3
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_t3:
    bsr.w   _bump

    | ---- case 4: jsr absolute, distance 4 ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_t4:
    jsr     _bump

    | ---- case 5: jsr indirect (ibranch), distance 2 ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t5
    addq.l  #1, %d4
    addq.l  #1, %d4
_t5:
    jsr     (%a1)

    | ---- case 6: two back-to-back calls at the redirect PC, distance 5 ----
    moveq   #1, %d0
    cmp.l   %d0, %d0
    beq.w   _t6
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
    addq.l  #1, %d4
_t6:
    bsr.w   _bump
    bsr.w   _bump2

    | ---- verify ----
    cmp.l   #7, %d5
    bne     _fail
    tst.l   %d4
    bne     _fail

    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a3)
_halt:
    bra     _halt

_bump:
    addq.l  #1, %d5
    rts

_bump2:
    addq.l  #1, %d5
    rts

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a3)
_halt_fail:
    bra     _halt_fail
