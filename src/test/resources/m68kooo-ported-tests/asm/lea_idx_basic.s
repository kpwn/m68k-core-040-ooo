| lea_idx_basic.s — Stage D-10 V2 LEA (d8,An,Xn.L*scale),An crack.
|
| Verifies:
|   A3 = A1 + (D2 * 4) + d8
|
| Exercises the brief-indexed V2 crack with Xn.L and scale=4 (2
| doubling phases).

    .text
    .org 0

_start:
    | A1 = 0x00104000, D2.L = 0x10  ⇒  A3 = 0x00104000 + 0x40 + 16 = 0x00104050
    move.l  #0x00104000, %a1
    move.l  #0x00000010, %d2
    lea     16(%a1, %d2.l*4), %a3

    cmp.l   #0x00104050, %a3
    bne     _fail

    | Scale=1 path: no doublings.  A3 = 0x00104100 + 4 - 8 = 0x000040FC.
    move.l  #0x00104100, %a1
    move.l  #0x00000004, %d2
    lea     -8(%a1, %d2.l), %a3

    cmp.l   #0x001040FC, %a3
    bne     _fail

    | Word-sized index: Xn.W sign-extended.  D2.W = 0xFFFE (= -2 sx),
    | scale = 2, so index contributes -4.  A3 = 0x00104200 - 4 = 0x001041FC.
    move.l  #0x00104200, %a1
    move.l  #0xFFFFFFFE, %d2
    lea     0(%a1, %d2.w*2), %a3

    cmp.l   #0x001041FC, %a3
    bne     _fail

    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_halt_fail:
    bra     _halt_fail
