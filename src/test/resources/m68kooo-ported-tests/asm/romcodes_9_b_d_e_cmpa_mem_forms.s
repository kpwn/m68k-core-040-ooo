| romcodes_9_b_d_e_cmpa_mem_forms.s -- CMPA.W/L direct and simple memory EA
|
| Covers low-risk 1011 decode cracks from ROM gaps:
|   cmpa.w Dn/Ay,An
|   cmpa.w (Ay)/(Ay)+/-(Ay)/(d16,Ay)/(d16,PC),An
|   cmpa.l (Ay)/(Ay)+/-(Ay)/(d16,PC),An
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | CMPA.W Dn sign-extends the data-register word.
    move.l  #0xfffffffe, %a0
    move.l  #0x0000fffe, %d0
    .word   0xb0c0              | cmpa.w %d0,%a0
    bne     _fail1

    | CMPA.W Ay uses the low source word.
    move.l  #0x00000012, %a0
    move.l  #0x00000012, %a1
    .word   0xb0c9              | cmpa.w %a1,%a0
    bne     _fail2

    | Indirect word source, negative value.
    lea     0x00109200, %a4
    move.l  #0x0000fff0, %d1
    move.w  %d1, (%a4)
    move.l  #0xfffffff0, %a0
    .word   0xb0d4              | cmpa.w (%a4),%a0
    bne     _fail3
    cmpa.l  #0x00109200, %a4
    bne     _fail3

    | Postincrement advances by one word.
    lea     0x00109220, %a4
    move.l  #0x00000004, %d1
    move.w  %d1, (%a4)
    move.l  #0x00000004, %a0
    .word   0xb0dc              | cmpa.w (%a4)+,%a0
    bne     _fail4
    cmpa.l  #0x00109222, %a4
    bne     _fail4

    | Predecrement reads after subtracting one word.
    lea     0x00109240, %a4
    move.l  #0x00000008, %d1
    move.w  %d1, (%a4)
    lea     0x00109242, %a4
    move.l  #0x00000008, %a0
    .word   0xb0e4              | cmpa.w -(%a4),%a0
    bne     _fail5
    cmpa.l  #0x00109240, %a4
    bne     _fail5

    | Displaced word source.
    lea     0x00109260, %a4
    move.l  #0x0000fffa, %d1
    move.w  %d1, 6(%a4)
    move.l  #0xfffffffa, %a0
    .word   0xb0ec, 0x0006      | cmpa.w 6(%a4),%a0
    bne     _fail6

    | PC-relative word source.
    move.l  #0xffffffee, %a0
    cmpa.w  _pc_word(%pc), %a0
    bne     _fail7

    | CMPA.L indirect source.
    lea     0x00109280, %a3
    move.l  #0x12345678, (%a3)
    move.l  #0x12345678, %a0
    .word   0xb1d3              | cmpa.l (%a3),%a0
    bne     _fail8

    | CMPA.L postincrement source.
    lea     0x001092a0, %a3
    move.l  #0x01020304, (%a3)
    move.l  #0x01020304, %a1
    .word   0xb3db              | cmpa.l (%a3)+,%a1
    bne     _fail9
    cmpa.l  #0x001092a4, %a3
    bne     _fail9

    | CMPA.L predecrement source.
    lea     0x001092c0, %a4
    move.l  #0x89abcdef, (%a4)
    lea     0x001092c4, %a4
    move.l  #0x89abcdef, %a2
    .word   0xb5e4              | cmpa.l -(%a4),%a2
    bne     _fail10
    cmpa.l  #0x001092c0, %a4
    bne     _fail10

    | PC-relative long source.
    move.l  #0x0badc0de, %a0
    cmpa.l  _pc_long(%pc), %a0
    bne     _fail11

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
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d7
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_fail9:
    move.l  #0xDEAD0009, %d7
    bra     _fail
_fail10:
    move.l  #0xDEAD000a, %d7
    bra     _fail
_fail11:
    move.l  #0xDEAD000b, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt

    .align 2
_pc_word:
    .word   0xffee
    .align 2
_pc_long:
    .long   0x0badc0de
