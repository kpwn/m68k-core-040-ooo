| lea_d8_pc_xn.s -- LEA / PEA (d8,PC,Xn),An  (task #199 / A8)
|
| Exercises V2 LEA + PEA indexed-src path with PC-rel base.
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Expected LEA targets we will verify by loading and comparing.
    | For LEA (d8,PC,Xn),An the effective address is:
    |   EA = pd_pc + 2 + sx(d8) + Xn*scale
    | We compute the oracle at runtime: pick d0=0, lea labels, then
    | verify A1 matches the label's runtime address.

    | Case 1: LEA _marker1(%pc,%d0.w),%a1 with d0=0 -> A1 = &_marker1.
    moveq   #0, %d0
    lea     _marker1(%pc,%d0.w), %a1
    lea     _marker1_ref(%pc), %a2
    cmp.l   %a2, %a1
    bne     _fail

    | Case 2: LEA (d8,PC,D2.L*4),A3 with d2=1 -> A3 = &_marker_tbl[1].
    moveq   #1, %d2
    lea     _marker_tbl(%pc,%d2.l*4), %a3
    lea     _marker_tbl+4(%pc), %a4
    cmp.l   %a4, %a3
    bne     _fail

    | Case 3: PEA (d8,PC,D4.W*2),-(A7).  Pick d4=0 and verify pushed value.
    lea     _stack_top, %a7
    moveq   #0, %d4
    pea     _marker2(%pc,%d4.w*2)
    move.l  (%a7)+, %d5
    lea     _marker2_ref(%pc), %a5
    cmp.l   %a5, %d5
    bne     _fail

_pass:
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

    .align 2
_marker1:
_marker1_ref:
    .long   0xcafef00d
_marker_tbl:
    .long   0x11111111
    .long   0x22222222            | &_marker_tbl[1] : case 2 target
    .long   0x33333333
_marker2:
_marker2_ref:
    .long   0xdeadbabe

    .align 4
    .space  64
_stack_top:
