| tst_full_memind.s -- TST through full-format memory-indirect source EA
|
| Covers the Q700 ROM frontier:
|   40806400: 4a70 81e2 0cbc ffed
|             tst.w @($0cbc)@(-19)
|
| Also checks byte/long siblings and null/word outer displacement forms.

    .text
    .org 0

_start:
    | Exact ROM shape: pointer at $0cbc, word target at pointer - 19.
    lea     0x00000cbc, %a6
    lea     0x00114213, %a1
    move.l  %a1, (%a6)
    move.w  #0x8001, -19(%a1)
    .word   0x4a70, 0x81e2, 0x0cbc, 0xffed
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    | Byte sibling, null outer displacement.  Base is suppressed, so A0
    | must not participate in the address.
    lea     0x00000cc0, %a6
    lea     0x00114240, %a2
    move.l  %a2, (%a6)
    move.b  #0x00, (%a2)
    lea     0x0badf00d, %a0
    .word   0x4a30, 0x81e1, 0x0cc0
    bne     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

    | Long sibling with word outer displacement.
    lea     0x00000cc4, %a6
    lea     0x00114280, %a3
    move.l  %a3, (%a6)
    move.l  #0x00000001, 4(%a3)
    .word   0x4ab0, 0x81e2, 0x0cc4, 0x0004
    beq     _fail
    bmi     _fail
    bvs     _fail
    bcs     _fail

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a6)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a6
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a6)
_halt_fail:
    bra     _halt_fail
