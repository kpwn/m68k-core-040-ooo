| bf_mem_ro_dyn_both_straddle.s — read-only memory-EA bitfields
| (BFTST/BFEXTU/BFEXTS/BFFFO) with BOTH offset AND width dynamic
| ({Dn:Dm}) whose field straddles the first longword, starts past it
| (offset >= 32), or uses a negative offset — plus STATIC-form
| straddle probes (off+width > 32 with literal offset/width).
|
| PRM §4.29-§4.36 unbounded-bit-array semantics; see
| bfextu_mem_dyn_offset_straddle.s (the landed dyn-off-only /
| dyn-wid-only window fix) for the sibling shapes.  This file guards
| the dyn-both window crack (ALU_BF_PACK imm[21] {wid,byte} +
| alu.v imm[21] "offset raw on src_b" descriptor) and the windowed
| static memory crack.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Data buffer at 0x40800400: bytes 01 23 45 67 89 AB CD EF 11 22 33 44
    move.l  #0x40800400, %a3
    move.l  #0x01234567, (%a3)
    move.l  #0x89ABCDEF, 4(%a3)
    move.l  #0x11223344, 8(%a3)

    | ---- Case 1: BFEXTU dyn-both, byte-aligned 32-bit straddle ----
    | BFEXTU (A3){D6=16:D5=32} -> bytes 2..5 = 0x456789AB.
    moveq   #16, %d6
    moveq   #32, %d5
    bfextu  (%a3){%d6:%d5}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail

    | ---- Case 2: BFEXTS dyn-both, straddle with sign-extension ----
    | BFEXTS (A3){D6=36:D5=16} -> bits 36..51 = 0x9ABC -> 0xFFFF9ABC.
    moveq   #36, %d6
    moveq   #16, %d5
    bfexts  (%a3){%d6:%d5}, %d0
    cmp.l   #0xFFFF9ABC, %d0
    bne     _fail

    | ---- Case 3: BFFFO dyn-both across the boundary ----
    | BFFFO (A3){D6=33:D5=8}: field bits 33..40 = 0b00010011 -> first
    | set bit at position 3 -> D0 = 33 + 3 = 36.
    moveq   #33, %d6
    moveq   #8, %d5
    bfffo   (%a3){%d6:%d5}, %d0
    cmp.l   #36, %d0
    bne     _fail

    | ---- Case 4: BFFFO dyn-both with NEGATIVE offset ----
    | A4 = A3+8; BFFFO (A4){D6=-16:D5=4}: field bits -16..-13 = high
    | nibble of byte 6 (0xC = 1100) -> first set at position 0 ->
    | D0 = -16 + 0 = -16 (full 32-bit signed result).
    lea     8(%a3), %a4
    moveq   #-16, %d6
    moveq   #4, %d5
    bfffo   (%a4){%d6:%d5}, %d0
    cmp.l   #-16, %d0
    bne     _fail

    | ---- Case 5: BFEXTU dyn-both, negative offset straddling back ----
    | BFEXTU (A4){D6=-24:D5=16} -> bytes A3+5,A3+6 = 0xABCD.
    moveq   #-24, %d6
    moveq   #16, %d5
    bfextu  (%a4){%d6:%d5}, %d0
    cmp.l   #0xABCD, %d0
    bne     _fail

    | ---- Case 6: BFTST dyn-both flags across the boundary ----
    | Field bits 34..35 = 00 -> Z=1.
    moveq   #34, %d6
    moveq   #2, %d5
    bftst   (%a3){%d6:%d5}
    bne     _fail
    | Field bits 36..37 = 10 -> Z=0, N=1.
    moveq   #36, %d6
    bftst   (%a3){%d6:%d5}
    beq     _fail
    bpl     _fail

    | ---- Case 7: BFEXTU dyn-both, width register = 0 (means 32) ----
    | BFEXTU (A3){D6=40:D5=0} -> bits 40..71 = bytes 5..8 = 0xABCDEF11.
    moveq   #40, %d6
    moveq   #0, %d5
    bfextu  (%a3){%d6:%d5}, %d0
    cmp.l   #0xABCDEF11, %d0
    bne     _fail

    | ---- Case 8: STATIC offset+width straddle (literal form) ----
    | BFEXTU (A3){#16:#32} -> 0x456789AB (same field as case 1 but
    | fully static — guards the windowed static memory crack).
    bfextu  (%a3){#16:#32}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail

    | ---- Case 9: STATIC non-byte-aligned straddle ----
    | BFEXTS (A3){#28:#8} -> bits 28..35 = 0x78 -> 0x78 (positive).
    bfexts  (%a3){#28:#8}, %d0
    cmp.l   #0x78, %d0
    bne     _fail

    | ---- Case 10: STATIC BFFFO straddle + BFTST straddle flags ----
    | BFFFO (A3){#30:#8}: bits 30..31 = low 2 bits of 0x67 = 11,
    | bits 32..37 = top 6 bits of 0x89 = 100010.  Field = 0b11100010
    | -> first set bit at position 0 -> D0 = 30 + 0 = 30.
    bfffo   (%a3){#30:#8}, %d0
    cmp.l   #30, %d0
    bne     _fail
    | BFTST (A3){#34:#2}: bits 34..35 = 00 -> Z=1.
    bftst   (%a3){#34:#2}
    bne     _fail

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
