| movec_cacr.s — round-trip write + read CACR (cache control).
|
| DEFERRED: MOVEC is stubbed (decode.v 0x4E7A/0x4E7B).  CACR state
| register is not wired in phase 2.1.  Enable after movec-vbr
| agent lands.
|
| Sequence:
|   1. Load D0 = 0x80008000 (both icache and dcache enable bits
|      per 68040 CACR layout: IE=bit31, DE=bit15).
|   2. MOVEC D0, CACR.
|   3. MOVEC CACR, D1 — read back.
|   4. CMP D0,D1 — equal → PASS.
|
| PASS: sentinel 0xC0FFEE00.
| FAIL: sentinel 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #0x80008000, %d0
    movec   %d0, %cacr
    movec   %cacr, %d1
    cmp.l   %d0, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
