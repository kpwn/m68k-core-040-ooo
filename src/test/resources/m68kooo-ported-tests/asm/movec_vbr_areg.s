| movec_vbr_areg.s — MOVEC VBR through address registers
|
| The ROM writes VBR through address registers while relocating the
| exception table.  The core's architectural register IDs encode
| A0-A7 as 8-15, so MOVEC extension bit ext[15] must map to 01rrr,
| not 10rrr.
|
| This test covers both directions:
|   - MOVEC A0,VBR must source A0 and update the exception vector base.
|   - MOVEC VBR,A1 must write A1 so software can read the base back.
| Then it raises a vector-2 bus error and expects the handler at VBR+8.
|
| PASS: readback matches and vector-2 dispatch reaches relocated handler.
| FAIL: readback mismatch, fallthrough, or wrong-vector dispatch.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    move.l  #0x00010000, %a0
    movec   %a0, %vbr
    move.l  #_handler, 0x00010008

    movea.l #0, %a1
    movec   %vbr, %a1
    move.l  %a1, %d0
    move.l  %a0, %d1
    cmp.l   %d1, %d0
    bne     _fail

    lea     0xAAAA0000, %a2
    move.l  (%a2), %d2

_fail:
    lea     0xFFFF0000, %a3
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a3)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a3
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a3)
_halt:
    bra     _halt
