| scc_mem_indexed.s -- Scc byte stores to brief indexed memory EAs
|
| Covers Scc (d8,An,Xn) byte destinations for brief scale-x1 forms.
| Checks true/false byte values, neighbour-byte preservation, CCR
| preservation, base+index+disp calculation, negative displacement,
| Dn.L, Dn.W sign-extended, and An.L indexes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Dn.L index with positive displacement: A0 + D1 + 0x12 = A0 + 0x17.
    lea     0x0010A000, %a0
    moveq   #5, %d1
    move.l  #0x11223344, 0x17(%a0)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so EQ is true
    seq     0x12(%a0, %d1.l)
    bne     _fail1                 | Scc must preserve Z=1
    move.l  0x17(%a0), %d0
    cmp.l   #0xff223344, %d0
    bne     _fail1

    | Dn.L index with negative displacement: A1 + D2 - 5 = A1 + 4.
    | NE is false while Z=1, so the stored byte must be 0x00.
    lea     0x0010A100, %a1
    moveq   #9, %d2
    move.l  #0xaabbccdd, 4(%a1)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so NE is false
    sne     -5(%a1, %d2.l)
    bne     _fail2                 | Scc must preserve Z=1
    move.l  4(%a1), %d1
    cmp.l   #0x00bbccdd, %d1
    bne     _fail2

    | Dn.W index sign-extension: low word 0xfffc indexes as -4.
    | A2 + 9 - 4 = A2 + 5, the second byte of the staged longword.
    lea     0x0010A200, %a2
    move.l  #0x0000fffc, %d3
    move.l  #0x55667788, 4(%a2)
    moveq   #1, %d5
    tst.l   %d5                    | Z=0, so NE is true
    sne     9(%a2, %d3.w)
    beq     _fail3                 | Scc must preserve Z=0
    move.l  4(%a2), %d4
    cmp.l   #0x55ff7788, %d4
    bne     _fail3

    | An.L index with negative displacement: A4 + A3 - 2 = A4 + 5.
    | EQ is false while Z=0, so the stored byte must be 0x00.
    lea     0x0010A300, %a4
    movea.l #7, %a3
    move.l  #0x99aabbcc, 4(%a4)
    moveq   #1, %d5
    tst.l   %d5                    | Z=0, so EQ is false
    seq     -2(%a4, %a3.l)
    beq     _fail4                 | Scc must preserve Z=0
    move.l  4(%a4), %d0
    cmp.l   #0x9900bbcc, %d0
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
