| move_ea_sr_ccr_direct.s — Dn-direct SR/CCR transfer coverage.
|
| Covers the V2 sysop forms that used to retire as SYS_NOP:
|   .word 0x44C0  MOVE.W D0,CCR
|   .word 0x46C1  MOVE.W D1,SR
|   .word 0x40C2  MOVE.W SR,D2
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | MOVE.W D0,CCR; directly following branch must see the restored C.
    moveq   #1, %d0
    .word   0x44C0
    bcc     _fail

    | Now restore Z from D0 and branch immediately.
    moveq   #4, %d0
    .word   0x44C0
    bne     _fail

    | MOVE.W D1,SR: S=1, IPL=7, all CCR bits set.
    move.l  #0x0000271F, %d1
    .word   0x46C1
    bvc     _fail
    bcc     _fail
    bpl     _fail
    bne     _fail

    | MOVE.W SR,Dn should write only the low word.  After the moveq #0
    | the live CCR is N=0,Z=1,V=0,C=0 with X=1 preserved from the prior
    | MOVE.W D1,SR (which loaded CCR=0x1F).  Net CCR = 0x14, so SR low
    | byte = 0x14 — NOT 0x1F.  Pre-CCR-rename SYS_MOVE_SR_DN bug used
    | the stale arch_sr[4:0] which still held 0x1F (the SR write's
    | last update); fix reads {arch_sr[15:5], arch_ccr_val} so SR
    | snapshots reflect the live CCR set by intervening flag writers.
    moveq   #0, %d2
    .word   0x40C2
    cmp.l   #0x00002714, %d2
    bne     _fail

    | move.l of 0xABCD0000 sets N=1 (sign bit), Z=0, V=0, C=0, X=1
    | (preserved from prior 0x14); CCR=0x18.  SR=0x2718.  D3 high word
    | preserved by MOVE.W → 0xABCD2718.
    move.l  #0xABCD0000, %d3
    .word   0x40C3
    cmp.l   #0xABCD2718, %d3
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
