| movec_vbr_roundtrip.s — MOVEC VBR roundtrip (Stage D-7)
|
| Supervisor-only.  Exercises the V2 MOVEC emission shape:
|   MOVEC A0, VBR   (SYS_MOVEC_WR + arch_src_a = A0, cr_sel = VBR)
|   MOVEC VBR, A1   (SYS_MOVEC_RD + arch_dst   = A1, cr_sel = VBR)
| Both forms must round-trip: the value written to VBR via A0 must be
| read back into A1.
|
| Additionally verifies dispatch uses the new VBR: install a vec-32
| handler at VBR+0x80 and fire TRAP #0.
|
| PASS: handler runs + A0==A1.
| FAIL: A0 != A1 OR handler missed.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    move.l  #0x00010000, %a0
    movec   %a0, %vbr

    movea.l #0, %a1
    movec   %vbr, %a1
    cmp.l   %a0, %a1
    bne     _fail

    | Install handler at VBR + 32*4 = 0x00010080.
    move.l  #_handler, 0x00010080
    trap    #0

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a2)
_halt_fail:
    bra     _halt_fail

_handler:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a2)
_halt:
    bra     _halt
