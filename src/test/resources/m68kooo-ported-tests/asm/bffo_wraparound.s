| bffo_wraparound.s — BFFFO with dynamic offset near 32-bit boundary.
| Checks that ROL wraparound (offset>0 where field spans [32-off..32)∪
| [0..off+width-32)) reports Musashi's PRM-correct FFO = offset + #leading-zeros.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | ---- BFFFO with dyn offset = 28, width = 8 (wrap across bit 31→0) ----
    | D0 = 0x80000001.  offset=28 rotates D0 left by 28 → ROL(0x80000001,28)
    |  = (0x80000001 << 28) | (0x80000001 >> 4)
    |  = 0x10000000 | 0x08000000 = 0x18000000.
    | width=8 → top 8 bits of 0x18000000 = 0x18 = 0b00011000.
    | FFO: first 1 is at bit 3 of the extracted byte (bit 3 from MSB = inc=3).
    | result = offset (28) + inc (3) = 31.
    move.l  #0x80000001, %d0
    move.l  #28, %d2
    bfffo   %d0{%d2:#8}, %d1
    move.l  #31, %d3
    cmp.l   %d3, %d1
    beq     1f
    bra     _fail
1:
    | ---- BFFFO all-zeros field, dyn offset 5, width 10 ----
    | result = offset + width = 5 + 10 = 15 (no bit found).
    move.l  #0x00000000, %d0
    move.l  #5, %d2
    bfffo   %d0{%d2:#10}, %d1
    move.l  #15, %d3
    cmp.l   %d3, %d1
    beq     2f
    bra     _fail
2:
    | ---- BFFFO dyn offset = 31, width = 2 (spans bits 31..32, wraps to 0) ----
    | D0 = 0xC0000000 (top 2 bits set).  offset=31, width=2.
    | field lives at bits [31, 0] (wraps).  field bits: D0[31]=1, D0[-1]→D0[31]?
    | Actually rotate left by 31: ROL(0xC0000000, 31) =
    |   (0xC0000000 << 31) | (0xC0000000 >> 1) =
    |   0x00000000 | 0x60000000 = 0x60000000.
    | But wait, 0xC0000000 << 31 in 32-bit = 0x00000000 (only bit 0 survives,
    | which is 0).  Hmm.  Let me redo: 0xC0000000 in binary =
    |   11000000_00000000_00000000_00000000.  Rotate left by 31 (= rotate
    |   right by 1) gives 01100000_00000000_00000000_00000000 = 0x60000000.
    | Top 2 bits of 0x60000000 = 01.  So bit 31 of extracted = 0, bit 30 = 1.
    | FFO first-1 from MSB: inc=1 (bit 31 is 0, bit 30 is 1).
    | result = 31 + 1 = 32.
    move.l  #0xC0000000, %d0
    move.l  #31, %d2
    bfffo   %d0{%d2:#2}, %d1
    move.l  #32, %d3
    cmp.l   %d3, %d1
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
