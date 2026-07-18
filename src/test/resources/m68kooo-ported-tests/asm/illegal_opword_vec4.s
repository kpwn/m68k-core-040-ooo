| illegal_opword_vec4.s — 0x4AFC explicit ILLEGAL → vec 4 (Stage D-7)
|
| Verifies V2 emits exc_vec=4 for the explicit ILLEGAL opword 0x4AFC.
| (Un-decoded opwords also fall through to vec 4 via the inline
| fallback, but that path is separately covered elsewhere.)
|
| Install a handler at mem[VBR + 4*4] = 0x00000010.  Execute 0x4AFC.
| The handler writes the PASS sentinel.  If dispatch doesn't fire the
| CPU speculatively advances past the opword and the fallthrough FAIL
| sentinel runs instead.
|
| PASS: handler writes C0FFEE00.
| FAIL: fallthrough writes DEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler, 0x00000010     | vector 4 @ 0x10

    .short  0x4afc                     | ILLEGAL

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
