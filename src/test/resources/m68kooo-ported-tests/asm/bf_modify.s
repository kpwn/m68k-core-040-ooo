| bf_modify.s — BFCLR / BFSET / BFCHG in-place on Dn.  Checks the
| non-overlapping bits of Dn are preserved while the field bits change.

    .text
    .org 0
_start:
    | ---- BFCLR: %d0 = 0xFFFFFFFF, clear bits 26..20 (offset=5, width=7) ----
    | Expected: d0 = 0xFFFFFFFF & ~0x07F00000 = 0xF80FFFFF.
    move.l  #0xFFFFFFFF, %d0
    bfclr   %d0{5:7}
    move.l  #0xF80FFFFF, %d7
    cmp.l   %d7, %d0
    beq     1f
    bra     _fail
1:

    | ---- BFSET: %d0 = 0, set bits 26..20 ----
    | Expected: d0 = 0x07F00000.
    moveq   #0, %d0
    bfset   %d0{5:7}
    move.l  #0x07F00000, %d7
    cmp.l   %d7, %d0
    beq     2f
    bra     _fail
2:

    | ---- BFCHG: %d0 = 0x0FFFFFFF, XOR bits 26..20 → 0x08OFFFFF ----
    | 0x0FFFFFFF XOR 0x07F00000 = 0x080FFFFF.
    move.l  #0x0FFFFFFF, %d0
    bfchg   %d0{5:7}
    move.l  #0x080FFFFF, %d7
    cmp.l   %d7, %d0
    beq     3f
    bra     _fail
3:

    | ---- BFCHG flag check: d0 = 0x07F00000 (field all 1), Z should be 0
    | pre-op (Musashi: FLAG_Z = *data & mask).  Post-op d0 = 0.
    move.l  #0x07F00000, %d0
    bfchg   %d0{5:7}
    | After: d0 must equal 0.
    tst.l   %d0
    beq     4f
    bra     _fail
4:

    | ---- BFCLR flag check on all-zero field: Z = 1 ----
    | d0 = 0xF80FFFFF → field bits 26..20 are 0, other bits 1.
    move.l  #0xF80FFFFF, %d0
    bfclr   %d0{5:7}
    | CCR.Z must be 1 because the pre-op field was zero.
    beq     5f
    bra     _fail
5:

    | ---- BFSET + N-flag: d0 = 0x80000000 after offset=1,width=2 ----
    | offset=1, width=2 means bits 30..29.  Pre-op d0[30:29] = 00 → N=0, Z=1.
    | Post-op d0 bits 30..29 = 11 → d0 = 0xE0000000.
    move.l  #0x80000000, %d0
    bfset   %d0{1:2}
    bpl     6f                       | N=0 (pre-op field MSB = d0[30] = 0)
    bra     _fail
6:
    move.l  #0xE0000000, %d7
    cmp.l   %d7, %d0
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
