| bfextu_mem_dyn_offset_straddle.s — dynamic-offset MEMORY bitfields
| whose field STRADDLES the first longword (offset+width > 32) or whose
| offset reaches past the first longword entirely (offset >= 32), plus
| a negative-offset case.
|
| PRM §4.29-§4.36: for a MEMORY operand the bitfield offset addresses an
| unbounded bit array anchored at the EA — byte address = EA + (offset
| arithmetically >> 3), residual bit offset = offset & 7.  The field may
| span up to 5 bytes.  This is NOT the register form's mod-32 window.
|
| Real-world consumer: the Q700 ROM boot-icon blit inner loop
| (ROM 0x408379f0: bfextu (%a3){%d6:32},%d0 with d6=16) — the broken
| mod-32-rotate implementation rendered the "?" floppy icon with its
| left/right 16px halves swapped on HW (2026-07-15).
|
| Regression guard for the decode_uop_assemble.v dyn-off-only memory
| crack (task #205 B4) + alu.v bf mem-window path.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Data buffer at 0x40800200: bytes 01 23 45 67 89 AB CD EF 11 22 33 44
    move.l  #0x40800200, %a3
    move.l  #0x01234567, (%a3)
    move.l  #0x89ABCDEF, 4(%a3)
    move.l  #0x11223344, 8(%a3)

    | ---- Case 1: the ROM boot-icon shape ----
    | BFEXTU (A3){D6=16:32} -> bits 16..47 = bytes 2..5 = 0x456789AB.
    | (Broken mod-32 rotate returns 0x45670123 — halves of long0 swapped.)
    moveq   #16, %d6
    bfextu  (%a3){%d6:32}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail

    | ---- Case 2: offset entirely past the first long ----
    | BFEXTU (A3){D6=40:32} -> bits 40..71 = bytes 5..8 = 0xABCDEF11.
    moveq   #40, %d6
    bfextu  (%a3){%d6:32}, %d0
    cmp.l   #0xABCDEF11, %d0
    bne     _fail

    | ---- Case 3: in-long control (old path got this right too) ----
    | BFEXTU (A3){D6=4:#8} -> bits 4..11 = 0x12.
    moveq   #4, %d6
    bfextu  (%a3){%d6:8}, %d0
    cmp.l   #0x12, %d0
    bne     _fail

    | ---- Case 4: non-byte-aligned straddle ----
    | BFEXTU (A3){D6=28:#8} -> bits 28..35 = low nibble of byte3 (7)
    | then high nibble of byte4 (8) = 0x78.
    moveq   #28, %d6
    bfextu  (%a3){%d6:8}, %d0
    cmp.l   #0x78, %d0
    bne     _fail

    | ---- Case 5: BFEXTS sign-extending straddle ----
    | BFEXTS (A3){D6=36:#16} -> bits 36..51 = 0x9ABC -> 0xFFFF9ABC.
    moveq   #36, %d6
    bfexts  (%a3){%d6:16}, %d0
    cmp.l   #0xFFFF9ABC, %d0
    bne     _fail

    | ---- Case 6: BFFFO across the boundary, full offset in result ----
    | BFFFO (A3){D6=33:#8}: field bits 33..40 = 0001_0011 -> first set
    | bit 3 positions in -> D0 = 33 + 3 = 36.
    moveq   #33, %d6
    bfffo   (%a3){%d6:8}, %d0
    cmp.l   #36, %d0
    bne     _fail

    | ---- Case 7: negative dynamic offset ----
    | A4 = buffer+8; D6 = -16 -> bits -16..-1 = bytes 6,7 = 0xCDEF.
    lea     8(%a3), %a4
    moveq   #-16, %d6
    bfextu  (%a4){%d6:16}, %d0
    cmp.l   #0xCDEF, %d0
    bne     _fail

    | ---- Case 8: (d16,An) EA with straddling field ----
    | BFEXTU 4(A3){D6=16:32} -> bytes 6..9 = 0xCDEF1122.
    moveq   #16, %d6
    bfextu  4(%a3){%d6:32}, %d0
    cmp.l   #0xCDEF1122, %d0
    bne     _fail

    | ---- Case 9: BFTST flags across the boundary ----
    | Field bits 34..35 = 00 -> Z=1.
    moveq   #34, %d6
    bftst   (%a3){%d6:2}
    bne     _fail
    | Field bits 36..37 = 10 -> Z=0, N=1 (field MSB = bit 36 = 1).
    moveq   #36, %d6
    bftst   (%a3){%d6:2}
    beq     _fail
    bpl     _fail

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
