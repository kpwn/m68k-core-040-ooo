| cmpa_abs_mem.s -- CMPA.W/L (xxx).{W,L},An absolute-source forms
|
| Exercises the exact Q700 ROM frontier:
|   4080cfda: b7f8 0bae  cmpa.l (0x0bae).W,%a3
| plus CMPA.L abs.L and CMPA.W abs.W/abs.L sibling forms.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Set X=1 so every CMPA case also proves X is preserved.
    moveq   #-1, %d0
    addq.l  #1, %d0

    | -- Exact ROM long absolute-short form ---------------------------
    lea     0x00000bae, %a0
    move.l  #0x00100010, (%a0)
    lea     0x00100020, %a3
    .word   0xb7f8, 0x0bae      | cmpa.l (0x0bae).W,%a3
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00100020, %a3
    bne     _fail
    moveq   #0, %d1
    addx.l  %d1, %d1
    cmp.l   #1, %d1
    bne     _fail

    | -- Long absolute-long form: equal sets Z ------------------------
    moveq   #-1, %d0
    addq.l  #1, %d0
    lea     0x00116000, %a0
    move.l  #0x00116020, (%a0)
    lea     0x00116020, %a4
    .word   0xb9f9, 0x0011, 0x6000  | cmpa.l (0x00116000).L,%a4
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmpa.l  #0x00116020, %a4
    bne     _fail
    moveq   #0, %d1
    addx.l  %d1, %d1
    cmp.l   #1, %d1
    bne     _fail

    | -- Word absolute-short form sign-extends 0xffff to -1 ----------
    moveq   #-1, %d0
    addq.l  #1, %d0
    lea     0x00000bb4, %a0
    move.w  #0xffff, (%a0)
    lea     0x00000000, %a5
    .word   0xbaf8, 0x0bb4      | cmpa.w (0x0bb4).W,%a5
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00000000, %a5
    bne     _fail
    moveq   #0, %d1
    addx.l  %d1, %d1
    cmp.l   #1, %d1
    bne     _fail

    | -- Word absolute-long form sign-extends 0x0001 -----------------
    moveq   #-1, %d0
    addq.l  #1, %d0
    lea     0x00116020, %a0
    move.w  #0x0001, (%a0)
    lea     0x00000000, %a6
    .word   0xbcf9, 0x0011, 0x6020  | cmpa.w (0x00116020).L,%a6
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcc     _fail
    cmpa.l  #0x00000000, %a6
    bne     _fail
    moveq   #0, %d1
    addx.l  %d1, %d1
    cmp.l   #1, %d1
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
_fail_halt:
    bra     _fail_halt
