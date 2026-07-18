| romcodes_9_b_d_e_suba_word_forms.s -- SUBA.W direct and simple memory EA
|
| Covers low-risk 1001 decode cracks from ROM gaps:
|   suba.w Dn,An
|   suba.w Ay,An
|   suba.w (Ay)/(Ay)+/-(Ay)/(d16,Ay),An
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Data-register word sources sign-extend before address subtraction.
    move.l  #0x00001000, %a0
    move.l  #0x0000fffe, %d0
    cmp.l   %d0, %d0
    .word   0x90c0              | suba.w %d0,%a0
    bne     _fail1              | SUBA must preserve CCR
    cmpa.l  #0x00001002, %a0
    bne     _fail1

    | Address-register direct word sources use the low word.
    move.l  #0x00002000, %a1
    move.l  #0x00000012, %a2
    cmp.l   %d0, %d0
    .word   0x92ca              | suba.w %a2,%a1
    bne     _fail2
    cmpa.l  #0x00001fee, %a1
    bne     _fail2

    | Indirect word source, negative value.
    lea     0x00109100, %a4
    move.l  #0x0000fff0, %d1
    move.w  %d1, (%a4)
    move.l  #0x00003000, %a0
    cmp.l   %d1, %d1
    .word   0x90d4              | suba.w (%a4),%a0
    bne     _fail3
    cmpa.l  #0x00003010, %a0
    bne     _fail3
    cmpa.l  #0x00109100, %a4
    bne     _fail3

    | Postincrement advances the source address by one word.
    lea     0x00109120, %a4
    move.l  #0x00000004, %d1
    move.w  %d1, (%a4)
    move.l  #0x00004000, %a0
    .word   0x90dc              | suba.w (%a4)+,%a0
    cmpa.l  #0x00003ffc, %a0
    bne     _fail4
    cmpa.l  #0x00109122, %a4
    bne     _fail4

    | Predecrement reads after subtracting one word from the source address.
    lea     0x00109140, %a4
    move.l  #0x00000008, %d1
    move.w  %d1, (%a4)
    lea     0x00109142, %a4
    move.l  #0x00005000, %a0
    .word   0x90e4              | suba.w -(%a4),%a0
    cmpa.l  #0x00004ff8, %a0
    bne     _fail5
    cmpa.l  #0x00109140, %a4
    bne     _fail5

    | Displaced word source with sign extension.
    lea     0x00109160, %a4
    move.l  #0x0000fffa, %d1
    move.w  %d1, 6(%a4)
    move.l  #0x00006000, %a0
    .word   0x90ec, 0x0006      | suba.w 6(%a4),%a0
    cmpa.l  #0x00006006, %a0
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
