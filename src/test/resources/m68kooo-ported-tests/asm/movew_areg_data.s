| movew_areg_data.s -- MOVE.W An,Dn
|
| Covers the Q700 ROM RAM-sizing helper instruction:
|   0x300f  move.w a7,d0

    .text
    .org 0

_start:
    | Exact ROM blocker: MOVE.W A7,D0 stores A7's low word into D0,
    | preserves D0's upper word, and updates NZVC from the moved word.
    lea     0x00208000, %a7
    move.l  #0x12345678, %d0
    .word   0x300f              | move.w %a7,%d0
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0x12348000, %d0
    bne     _fail

    | Zero low word sets Z and still preserves the destination upper word.
    lea     0x00210000, %a7
    move.l  #0xABCD5678, %d0
    .word   0x300f              | move.w %a7,%d0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xABCD0000, %d0
    bne     _fail

    | Positive low word clears N/Z/V/C.
    lea     0x00201234, %a7
    move.l  #0xCAFE0000, %d0
    .word   0x300f              | move.w %a7,%d0
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail
    cmp.l   #0xCAFE1234, %d0
    bne     _fail

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
