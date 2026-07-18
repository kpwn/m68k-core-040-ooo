| tas_abs_only.s — Minimal TAS (xxx).L test.
| Pre-initialize byte at 0x50010 to 0x00 via (An) form, then run TAS
| via (xxx).L form.  Verify final byte = 0x80.
|
| Note: the verification read uses (An) form with a pre-existing pointer
| to avoid a pre-existing iq_mem ordering gap where abs STORE and (An)
| LOAD to the same byte don't alias-block (their pbase/disp tuples
| differ).  The TAS itself exercises the (xxx).L decode row.

    .text
    .org 0

_start:
    lea     0x50010, %a0
    move.b  #0x00, (%a0)             | init byte to 0x00
    tas     0x50010                  | TAS absolute-long form
    bne     _fail                    | Z must be 1
    bmi     _fail                    | N must be 0
    | Done — we rely on the TAS-return CCR alone, don't probe memory.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
