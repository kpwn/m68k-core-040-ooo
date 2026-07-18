| bf_dynamic_offset.s — BFEXTU/BFCLR with dynamic offset (Do=1, Dw=0).
| Exercises the 1-µop dynamic-offset path where the offset comes from
| a Dn register (low 5 bits), not the extension-word literal.
| Width remains a static literal.  Musashi: offset &= 31.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFEXTU with dynamic offset ----
    | D0 = source field, D4 = offset = 8, width literal = 8 → extract
    | byte at [8:16).  D0 = 0x12_34_56_78, bits [8:16) = byte 2 = 0x34.
    | Musashi rotates left by offset so the 8 field-bits land at
    | positions [24:32).  field_rol = ROL(0x12345678, 8) = 0x34567812.
    | Then >> 24 = 0x34.
    move.l  #0x12345678, %d0
    move.l  #8, %d4
    bfextu  %d0{%d4:8}, %d1
    move.l  #0x34, %d2
    cmp.l   %d2, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFEXTU with dynamic offset = 0 (boundary) ----
    | D4=0, width=8 → extract top 8 bits = 0x12
    move.l  #0x12345678, %d0
    moveq   #0, %d4
    bfextu  %d0{%d4:8}, %d1
    moveq   #0x12, %d2
    cmp.l   %d2, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFEXTU with dynamic offset that wraps (offset mod 32) ----
    | D4=35 → 35 & 31 = 3.  D0 = 0x80000000.  field_rol = ROL(0x80000000, 3)
    | = 0x00000004.  Extract width 8 → 0x00000004 >> 24 = 0.
    move.l  #0x80000000, %d0
    move.l  #35, %d4
    bfextu  %d0{%d4:8}, %d1
    tst.l   %d1
    beq     3f
    bra     _fail
3:
    | ---- BFCLR with dynamic offset ----
    | D0 = 0xFFFFFFFF, D4 = 4, width 8.
    | mask = 0xFF000000, ROR by 4 = 0x0F00000F + overflow wrap.
    | Actually: 0xFF000000 rotated right 4 = 0x0FF00000 | (0xFF000000 << 28)
    |                                      = 0x0FF00000 | 0xF0000000
    |                                      = 0xFFF00000
    | Hmm let me recompute. ROR_32(0xFF000000, 4)
    |   = (0xFF000000 >> 4) | (0xFF000000 << 28)
    |   = 0x0FF00000 | (0xFF000000 << 28).
    | 0xFF000000 << 28 clips to 0x00000000 in 32-bit (only bit 4..11 of the
    | 64-bit shl survive? no wait, << in C on uint32 just wraps).  Actually
    | 0xFF000000 << 28: the low byte of 0xFF000000 is 0, so (0) << 28 = 0.
    | So ROR_32(0xFF000000, 4) = 0x0FF00000.
    | Hmm that doesn't match the rotation semantics I expected.  Let me
    | just verify the final result against what Musashi would produce.
    | BFCLR clears: D0 &= ~mask = 0xFFFFFFFF & ~0x0FF00000 = 0xF00FFFFF.
    move.l  #0xFFFFFFFF, %d0
    move.l  #4, %d4
    bfclr   %d0{%d4:8}
    move.l  #0xF00FFFFF, %d3
    cmp.l   %d3, %d0
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
