| bchg_dyn_mem_ccr.s — Isolated BCHG Dn, mem CCR preservation test.
|
| The full bchg_ccr_step.s fails — narrow to this case to repro cleanly.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Seed byte 0x00 at 0x00020000.
    move.b  #0x00, 0x00020000

    | CCR := NV (=0x0a).
    move.w  #0x000a, %ccr

    | BCHG D0, mem.  D0=0 → bit 0.  Byte was 0, so Z should set to 1.
    moveq   #0, %d0
    bchg    %d0, 0x00020000

    | Read CCR.
    move.w  %sr, %d3
    and.l   #0x1f, %d3

    | Write CCR to a known address so we can read it.
    move.l  %d3, 0x00030000

    | PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
