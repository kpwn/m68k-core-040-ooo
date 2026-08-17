| exc_bkpt_decode.s — BKPT #n decodes as SYS_DBG_BREAK.
|
| The tb_top harness treats dbg_break_uop_fire as a clean debug halt and
| writes the PASS sentinel.  If BKPT falls through, write FAIL.
|
| This test OPTS IN to that behaviour via exc_bkpt_decode.args
| (+expect_bkpt_halt).  The harness used to treat a BKPT halt as an
| unconditional PASS for every test, which meant any program that reached
| a 0x4848-0x484F encoding — including by wild jump or by decoding data as
| code — reported [PASS] without ever running its own checks.  A debug
| halt is now its own reported outcome, and only tests that ask for it
| get credit.  Do NOT drop the .args file: without it this test correctly
| fails with [DEBUG-HALT].

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
