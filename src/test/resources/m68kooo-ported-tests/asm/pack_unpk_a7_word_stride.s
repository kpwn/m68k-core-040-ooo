| pack_unpk_a7_word_stride.s — A7 on the WORD side of PACK / UNPK
| adjusts by 2 (a word access is 2 bytes; the A7 byte-alignment rule
| does not stack on top of it).
|
| PRM §4.146: PACK's SOURCE "is a 16-bit word".
| PRM §4.190: UNPK's DESTINATION is "the resulting word".
| A word access through -(An) adjusts by 2 for every An including A7,
| so:
|     pack -(%a7),-(%ax)  ->  A7 -= 2
|     unpk -(%ay),-(%a7)  ->  A7 -= 2
|
| ── ASSERTED AGAINST THE PRM, NOT AGAINST MUSASHI ─────────────────
| Musashi v4.60 DISAGREES here, and it is wrong.  Its m68k_in.c builds
| the word out of two BYTE accesses and therefore calls EA_A7_PD_8()
| — which is `REG_A[7] -= 2` — TWICE on the word side:
|     pack, 16, mm, ay7 :  ea = EA_A7_PD_8(); ... ea = EA_A7_PD_8();
|     unpk, 16, mm, ax7 :  ea = EA_A7_PD_8(); ... ea = EA_A7_PD_8();
| moving A7 by 4 where a single word access moves it by 2.  Measured:
| Musashi gives A7 = 0x001000FC where the PRM requires 0x001000FE.
|
| This is the same root cause as the PACK/UNPK memory BYTE-ORDER
| deviation recorded in docs/isa_status.md: Musashi decomposes a
| documented word access into two byte accesses and gets both the
| endianness and the A7 stride wrong as a result.  We follow the PRM.
|
| Because of this, the memory-form PACK/UNPK emitters in
| tools/fuzz/gen_program.py deliberately never place A7 on the word
| side — see the comment there.  This directed test is the coverage for
| that excluded shape.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | ── PACK with A7 as SOURCE (word side) ─────────────────────────
    | Source: word 0x3939 at 0x00100100; A7 = 0x00100102 -> 0x00100100.
    | Dest:   A3 = 0x00110042 -> 0x00110041 (byte, non-A7, -1).
    | packed = ((0x3939>>4)&0xF0)|(0x3939&0x0F) = 0x90|0x09 = 0x99.
    move.w  #0x3939, 0x00100100
    move.l  #0xCCCCCCCC, 0x00110040
    lea     0x00110042, %a3
    lea     0x00100102, %a7
    pack    -(%a7), -(%a3), #0
    move.l  %a7, %d0
    move.l  %a3, %d1
    lea     0x00010000, %a7

    cmp.l   #0x00100100, %d0          | -2, NOT Musashi's -4 (0x001000FE)
    bne     _fail
    cmp.l   #0x00110041, %d1
    bne     _fail
    move.b  0x00110041, %d2
    and.l   #0xFF, %d2
    cmp.l   #0x99, %d2
    bne     _fail

    | ── UNPK with A7 as DESTINATION (word side) ────────────────────
    | Source: byte 0x77 at 0x00110100; A2 = 0x00110101 -> 0x00110100
    |         (byte, non-A7, -1).
    | Dest:   A7 = 0x00100302 -> 0x00100300 (word, -2).
    | word = ((0x77<<4)&0x0F00)|(0x77&0x0F) + 0x3030
    |      = 0x0707 + 0x3030 = 0x3737.
    move.b  #0x77, 0x00110100
    move.l  #0xDDDDDDDD, 0x00100300
    lea     0x00110101, %a2
    lea     0x00100302, %a7
    unpk    -(%a2), -(%a7), #0x3030
    move.l  %a7, %d0
    move.l  %a2, %d1
    lea     0x00010000, %a7

    cmp.l   #0x00100300, %d0          | -2, NOT Musashi's -4 (0x001002FE)
    bne     _fail
    cmp.l   #0x00110100, %d1
    bne     _fail
    move.w  0x00100300, %d2
    and.l   #0xFFFF, %d2
    cmp.l   #0x3737, %d2
    bne     _fail

    | ── BOTH operands A7 ───────────────────────────────────────────
    | PACK -(A7),-(A7): word source -2, then byte destination via A7 -2,
    | so A7 moves by 4 in total and the packed byte lands where the
    | source word's high byte was.
    |   A7 = 0x00100402
    |   word source at 0x00100400 = 0x3535 -> A7 = 0x00100400
    |   byte dest    at 0x001003FE          -> A7 = 0x001003FE
    |   packed = 0x55
    move.w  #0x3535, 0x00100400
    move.l  #0xEEEEEEEE, 0x001003FC
    lea     0x00100402, %a7
    pack    -(%a7), -(%a7), #0
    move.l  %a7, %d0
    lea     0x00010000, %a7

    cmp.l   #0x001003FE, %d0
    bne     _fail
    move.b  0x001003FE, %d2
    and.l   #0xFF, %d2
    cmp.l   #0x55, %d2
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
