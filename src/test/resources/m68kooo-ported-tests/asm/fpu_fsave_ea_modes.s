| fpu_fsave_ea_modes.s — FSAVE HANGS THE CORE on (d16,An), (xxx).W and (xxx).L.
|
| Isolated 2026-09-12 while triaging fsave_frestore_basic. FSAVE works for the
| register-indirect forms and hangs for the displaced/absolute ones:
|
|     fsave -(%a7)    OK   A7 -= 52, header 0x41300000, body zero-filled
|     fsave (%a0)     OK   header 0x41300000
|     fsave -(%a0)    OK   A0 -= 52
|     fsave 8(%a0)    HANG   <-- (d16,An)
|     fsave 0x1F00.w  HANG   <-- (xxx).W
|     fsave 0x00020010 HANG  <-- (xxx).L
|
| These are GENUINE HANGS, not the "sentinel written as 0 reads as no-write"
| harness artifact: each probe ORs 0xA0000000 into the reported value, so any
| completion writes a non-zero sentinel. Nothing was written at all.
|
| Not a malformed test — gas emits the architecturally correct encodings:
|     f328 0008   fsave %a0@(8)
|     f310        fsave %a0@
|     f338 1f00   fsave 0x1f00
| and a control program with the SAME shape minus the FSAVE completes normally
| (and `fsave (%a0)` in that exact shape returns the correct 0x41300000 header).
|
| Why it matters: FSAVE/FRESTORE is FPSP-critical. The Q700 ROM's FPSP uses
| `fsave %sp@-` (the predecrement form, which works), so this does not break the
| common path — but any FPSP or OS path using a displaced/absolute FSAVE would
| hang the machine outright rather than fault.
|
| This test covers ONLY the (d16,An) form; the two absolute forms fail
| identically and are listed above rather than given separate tests.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0E01 — header at 8(A0) is not 0x41300000
|   (a HANG means FSAVE (d16,An) never completed — the defect this test exists for)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a0
    move.l  #0xDEADBEEF, 8(%a0)        | poison the header slot

    fsave   8(%a0)                     | (d16,An) — HANGS TODAY

    move.l  8(%a0), %d1
    cmp.l   #0x41300000, %d1
    bne     _fail

    move.l  #0xC0FFEE00, %d2
    bra     _done
_fail:
    move.l  #0xDEAD0E01, %d2
_done:
    lea     PASS_SENT, %a2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
