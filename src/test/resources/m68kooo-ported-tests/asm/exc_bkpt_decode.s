| exc_bkpt_decode.s — BKPT #n decodes as SYS_DBG_BREAK.
|
| The tb_top harness treats dbg_break_uop_fire as a clean debug halt and
| writes the PASS sentinel.  If BKPT falls through, write FAIL.

    .text
    .org 0

_start:
    nop
_bkpt_target:
    .short  0x4848              | BKPT #0

_fail_no_break:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD4848, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
