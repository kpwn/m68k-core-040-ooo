| bchg_dyn_mem_ccr_check.s — Same as above but PASS sentinel iff CCR=0x0e.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.b  #0x00, 0x00020000

    | KEY: set D0 BEFORE setting CCR, since MOVEQ writes CCR (N/Z + clear V/C).
    moveq   #0, %d0
    move.w  #0x000a, %ccr                 | CCR=NV (=0x0a), now safe — D0 already set.

    bchg    %d0, 0x00020000               | BCHG should preserve N/V/C/X, set Z=1.

    | Read CCR immediately into %d3 via MOVE-from-SR (does NOT alter CCR).
    move.w  %sr, %d3
    | Cannot use AND.L #imm,Dn (sets CCR).  But we already captured SR in d3.
    | Mask via Dn-to-Dn move + AND.L on a SECOND register so the snapshot in d3
    | is preserved — actually d3 was copied from SR so it's safe; we just need
    | to compare the low 5 bits.  Use BTST instead of AND, but BTST writes Z.
    | Simplest: cmp the low 5 bits using a precomputed mask via SR shifts.
    | But we can also just AND in-place since we ALREADY have CCR in d3.
    and.l   #0x1f, %d3
    cmp.l   #0x0e, %d3
    bne     _fail

    | PASS.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
_fail:
    lea     0xFFFF0000, %a0
    | Encode actual CCR in the FAIL sentinel: 0xDEADxx where xx = actual CCR.
    move.l  #0xDEAD0000, %d0
    or.l    %d3, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
