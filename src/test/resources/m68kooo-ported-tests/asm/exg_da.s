| exg_da.s -- task #220 / E1 directed: EXG Dn,An (cross-type).
|
| Verifies the Dn,Ay cross-type form of EXG (opword pattern
| 1100_xxx_1_10001_yyy).  This is the shape used by the Q700 ROM
| at 0x40800A90 (0xC78F = EXG D3,A7).  After the swap the data
| register contains the old An value and An contains the old Dn
| value, both as full 32-bit copies.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | D-side payload is a non-trivial 32-bit value.  Put the An side
    | somewhere visible too.
    move.l  #0xA5A5A5A5, %d0
    lea     0x00080000, %a3

    | Set CCR (Z=0, N=0, V=0, C=0) so we can verify EXG leaves them.
    moveq   #1, %d7
    tst.l   %d7

    | Cross-type swap.  After: D0 = 0x00080000, A3 = 0xA5A5A5A5.
    exg     %d0, %a3

    | Z must stay 0.
    beq     _fail

    cmp.l   #0x00080000, %d0
    bne     _fail
    move.l  %a3, %d1
    cmp.l   #0xA5A5A5A5, %d1
    bne     _fail

    | Round-trip back.
    exg     %d0, %a3
    cmp.l   #0xA5A5A5A5, %d0
    bne     _fail
    move.l  %a3, %d1
    cmp.l   #0x00080000, %d1
    bne     _fail

    | Q700 ROM frontier shape: EXG D3,A7 (0xC78F) — distinct from the
    | D0/A3 round-trip above so a wrong arch-index packing in the
    | assembler can't silently get masked by the earlier checks.
    move.l  #0x00180000, %d3
    lea     0x40800100, %a7        | safe non-zero stack pointer
    .word   0xc78f                 | exg %d3,%a7
    cmp.l   #0x40800100, %d3
    bne     _fail
    move.l  %a7, %d2
    cmp.l   #0x00180000, %d2
    bne     _fail

    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_fail_halt:
    bra     _fail_halt
