| cmpa_w_d8_an_xn_src.s -- CMPA.W (d8,An,Xn),An (V2 indexed-src + word-ext).
|
| Task #222 (E6) — CMPA.W requires the V2 word-extend path: load a
| word, sign-extend to long, then CMP against An.  The indexed-src
| crack carries `sem_alu_word_ext` to add the EXT phase between LOAD
| and CMP.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | CMPA.W with negative word — sign-extends to 0xFFFFnnnn.
    | A0 = 0xFFFFFF00, mem word at 0x00134004 = 0xFF00 (sx → 0xFFFFFF00).
    | Expected: A0 - sx(mem) == 0 → Z=1.
    movea.l #0xFFFFFF00, %a0
    lea     0x00134000, %a1
    lea     0x00134004, %a6
    move.w  #0xFF00, (%a6)
    moveq   #4, %d3
    .word   0xb0f1, 0x3000       | cmpa.w (0,A1,D3.W*1),A0   off=4
    bne     _fail

    | CMPA.W with positive word, A1 > sx(mem) → C=0,N=0.
    movea.l #0x00010000, %a2
    lea     0x00134100, %a3
    lea     0x00134104, %a6
    move.w  #0x1234, (%a6)
    moveq   #2, %d4
    .word   0xb4f3, 0x4200       | cmpa.w (0,A3,D4.W*2),A2   off=4
    bcs     _fail
    bmi     _fail
    beq     _fail

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
