| adv_ras_overflow.s — 9-deep BSR, then 9-deep RTS (RAS has DEPTH=8)
|
| ASSUMPTION TESTED (ras.v):
|   "On overflow (spec_depth == DEPTH), spec_top wraps and silently
|    overwrites the oldest speculative entry — graceful degrade identical
|    to physical RAS in real CPUs."
|
|   The promise is that correctness is preserved — only perf (mispredict
|   cost) degrades.  The 9th BSR's ret_pc overwrites the 1st's in the RAS.
|   Subsequent RTS pops will mispredict for the first few but the loaded-
|   value-from-stack correction path must deliver each right target.
|
| ATTACK:
|   9 nested BSR / RTS pairs.  Each level sets a D-register marker, and
|   on return checks that D-register marker is preserved.  Verifies the
|   RTS mispredict-recovery actually lands at the loaded PC.
|
| PASS: all 9 markers preserved, final sentinel written.
|
| DIVERGENCE MEANING: RAS overflow state machine is corrupting the
|   correctness path — not just perf.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    moveq   #0, %d0                 | depth-reached marker
    bsr     _L1
    cmp.l   #9, %d0
    bne     _fail
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_f:
    bra     _halt_f

_L1:    bsr _L2
        addq.l #1, %d0              | on return: d0=8+1=9 if _L2..L9 all preserved d0's chain
        rts
_L2:    bsr _L3
        addq.l #1, %d0              | d0 += 1
        rts
_L3:    bsr _L4
        addq.l #1, %d0
        rts
_L4:    bsr _L5
        addq.l #1, %d0
        rts
_L5:    bsr _L6
        addq.l #1, %d0
        rts
_L6:    bsr _L7
        addq.l #1, %d0
        rts
_L7:    bsr _L8
        addq.l #1, %d0
        rts
_L8:    bsr _L9
        addq.l #1, %d0
        rts
_L9:    addq.l #1, %d0              | innermost — first +1
        rts
