| pea_idx_long.s — Stage D-10 V2 PEA (d8,An,Xn.L*scale).
|
| Verifies:
|   (SP_new) = A1 + (D2 * 4) + d8
|   A7 post = A7 pre - 4

    .text
    .org 0

_start:
    | Set up stack somewhere sane at 0x00102000.
    move.l  #0x00102000, %a7

    | A1 = 0x00104000, D2.L = 0x10, d8 = 32
    | Expected EA = 0x00104000 + 0x40 + 32 = 0x00104060
    move.l  #0x00104000, %a1
    move.l  #0x00000010, %d2
    pea     32(%a1, %d2.l*4)

    | A7 should have dropped by 4: was 0x00102000 → now 0x00101FFC.
    move.l  %a7, %d5
    cmp.l   #0x00101FFC, %d5
    bne     _fail

    | Peek top-of-stack — should be the EA 0x00104060.
    move.l  (%a7), %d6
    cmp.l   #0x00104060, %d6
    bne     _fail

    | Scale=2 check with sign-extended Xn.W.  D2.W = 0xFFFE (= -2 sx),
    | scale=2, disp=0.  A1 = 0x00103000.  EA = 0x00103000 - 4 = 0x00102FFC.
    move.l  #0x00103000, %a1
    move.l  #0xFFFFFFFE, %d2
    pea     0(%a1, %d2.w*2)
    move.l  (%a7), %d6
    cmp.l   #0x00102FFC, %d6
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
