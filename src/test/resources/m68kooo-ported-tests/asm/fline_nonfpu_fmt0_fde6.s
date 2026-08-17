| Exact System 7.5.3 frontier: 0xFDE6 is generic coprocessor-ID 6
| line-F, not an on-chip FPU instruction.  MAME routes it through
| m68ki_exception_1111(), which builds a format-$0 vector-11 frame.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x0000002C
_frontier:
    .short  0xFDE6
    move.l  #0xDEADBEEF, 0xFFFF0000
    bra     .

_handler:
    cmp.w   #0x002C, 6(%sp)
    bne     _bad_format
    cmp.l   #_frontier, 2(%sp)
    bne     _bad_pc
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_bad_format:
    move.l  #0xDEAD202C, 0xFFFF0000
    bra     .

_bad_pc:
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .
