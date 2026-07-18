| chk2_cmp2_illegal_ea_traps.s — CMP2 with EA shapes the V2 crack does
| not implement must take a CLEAN vec-4 ILLEGAL (no wedge, no garbage
| load).  Pinned by the 2026-07-15 v2_cc2_ea_ok_f3 fire-gate fix:
|   PRM-illegal modes  : Dn, An, (An)+, -(An), #imm  (Musashi also
|                        ILLEGALs these — §4.39 control class only)
|   fail-safe follow-up: brief-indexed, memind, PC-indexed brief,
|                        full-format no-memind — legal 68020+ EAs whose
|                        EA-compute crack is not yet implemented; the
|                        old gate admitted them with a WRONG EA.
| Each case arms a continuation label in A5; the vec-4 handler counts
| the trap in D5 and resumes there.  Reaching the instruction after a
| case without trapping means the decoder executed it — FAIL.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_h4, 0x00000010          | vec 4 handler
    moveq   #0, %d5
    lea     0x00020000, %a0
    moveq   #0, %d0
    move.l  #5, %d1

    lea     _c2, %a5
    .word   0x04C0, 0x1000            | cmp2.l %d0, %d1        (Dn)
    bra     _fail
_c2:
    lea     _c3, %a5
    .word   0x04C8, 0x1000            | cmp2.l %a0, %d1        (An)
    bra     _fail
_c3:
    lea     _c4, %a5
    .word   0x04D8, 0x1000            | cmp2.l (%a0)+, %d1     (postinc)
    bra     _fail
_c4:
    lea     _c5, %a5
    .word   0x04E0, 0x1000            | cmp2.l -(%a0), %d1     (predec)
    bra     _fail
_c5:
    lea     _c6, %a5
    .word   0x04FC, 0x1000            | cmp2.l #, %d1          (imm)
    bra     _fail
_c6:
    lea     _c7, %a5
    cmp2.l  (0,%a0,%d0.l), %d1        | brief indexed
    bra     _fail
_c7:
    lea     _c8, %a5
    cmp2.l  ([0,%a0]), %d1            | full-format memind
    bra     _fail
_c8:
    lea     _c9, %a5
    .word   0x04FB, 0x1000, 0x0000    | cmp2.l (0,%pc,%d0.w), %d1 (PC-idx)
    bra     _fail
_c9:
    lea     _c10, %a5
    .word   0x04F0, 0x1000, 0x0160, 0x0010  | cmp2.l (16,%a0) full-format
    bra     _fail                            | (ext[8]=1, IS=1, bd.W)
_c10:
    cmp.l   #9, %d5                   | all nine must have trapped
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_h4:
    addq.l  #1, %d5
    move.l  %a5, (2,%a7)              | resume at the armed continuation
    rte
