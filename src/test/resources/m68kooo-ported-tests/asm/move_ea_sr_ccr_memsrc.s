| move_ea_sr_ccr_memsrc.s — (An)-source SR/CCR transfer coverage.
|
| Covers source-memory forms that used to retire as SYS_NOP:
|   .word 0x44D0  MOVE.W (A0),CCR
|   .word 0x46D1  MOVE.W (A1),SR
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | MOVE.W (A0),CCR; following branch must see restored C.
    lea     _ccr_c, %a0
    .word   0x44D0
    bcc     _fail

    | Restore Z through memory, then branch immediately.
    lea     _ccr_z, %a0
    .word   0x44D0
    bne     _fail

    | MOVE.W (A1),SR: S=1, IPL=7, all CCR bits set.
    lea     _sr_full, %a1
    .word   0x46D1
    bvc     _fail
    bcc     _fail
    bpl     _fail
    bne     _fail

    | Read SR back through the already-implemented direct form.  The
    | moveq #0 updates CCR (N=0,Z=1,V=0,C=0; X preserved from the prior
    | MOVE.W (A1),SR load which set X=1).  Live CCR after the moveq is
    | 0x14; SR low byte tracks live CCR (post-CCR-rename fix to
    | SYS_MOVE_SR_DN), so the snapshot reads 0x2714 — not the stale
    | 0x271F that the pre-fix SYS_MOVE_SR_DN returned.
    moveq   #0, %d2
    .word   0x40C2
    cmp.l   #0x00002714, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

    .align 2
_ccr_c:
    .word   0x0001
_ccr_z:
    .word   0x0004
_sr_full:
    .word   0x271F
