| Diagnostic, intentionally outside the default ported corpus until implemented.
| MC68040 UM 8.4.6.7 normal physical write error requires valid WB1 with
| fault address and memory-aligned data. No RTE/restart assumptions are made.
    .text
    .org 0
_start:
    lea     0x00010000, %a7
    move.l  #_buserr, 0x00000008
    moveq   #0, %d0
    movec   %d0, %cacr
    lea     0xaaaa0000, %a0
_faulting_store:
    move.l  #0x12345678, (%a0)
    move.l  #0xbadf0001, 0xffff0000
    bra     .
_buserr:
    cmpi.w  #0x7008, 6(%a7)
    bne     _bad_format
    | Logical FA is the original aligned store address, with MMU disabled.
    cmpi.l  #0xaaaa0000, 20(%a7)
    bne     _bad_address
    | WB1S is a word at +0x12; its V bit is bit7 of the low byte at +0x13.
    btst    #7, 19(%a7)
    beq     _missing_wb1
    cmpi.l  #0xaaaa0000, 40(%a7)
    bne     _bad_wb_address
    cmpi.l  #0x12345678, 44(%a7)
    bne     _bad_wb_data
    move.l  #0xc0ffee00, 0xffff0000
    bra     .
_bad_format:
    move.l  #0xbadf0002, 0xffff0000
    bra     .
_bad_address:
    move.l  #0xbadf0003, 0xffff0000
    bra     .
_missing_wb1:
    move.l  #0xbadf0004, 0xffff0000
    bra     .
_bad_wb_address:
    move.l  #0xbadf0005, 0xffff0000
    bra     .
_bad_wb_data:
    move.l  #0xbadf0006, 0xffff0000
    bra     .
