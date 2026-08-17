| unpk_mem_mem.s — UNPK -(A1),-(A0),#$3030
|
| PRM §4.190: UNPK reads a BYTE through -(Ay), splits it into two
| nibbles as ((src<<4)&0x0F00)|(src&0x0F), adds the 16-bit adjustment,
| and "the resulting word is written to the destination" through
| -(Ax).  On a big-endian machine a word write puts bits [15:8] at the
| LOWER address, so the destination is exactly one STORE.W at (A0-2).
| Classic use: BCD byte → ASCII digit pair with adj = 0x3030.
|
| ── HEADER CORRECTION (2026-08-09) ────────────────────────────────
| The previous revision of this comment stated the byte placement
| exactly backwards ("Musashi writes 0x35 at (A0-1), 0x32 at (A0-2)"),
| which made the assertions below look like they agreed with Musashi.
| They do not.  Musashi's actual m68k_in.c `unpk, 16, mm, .` is:
|     ea = EA_AX_PD_8(); write_8(ea, (src >> 8) & 0xff);   <- HIGHER addr
|     ea = EA_AX_PD_8(); write_8(ea, src & 0xff);          <- LOWER addr
| i.e. it puts the HIGH byte at the HIGHER address — byte-swapped
| relative to a big-endian word write.
|
| We follow the PRM, not Musashi: KNOWN DELIBERATE DEVIATION, see
| docs/isa_status.md.  The PRM reading is the only one under which UNPK
| does its documented job — BCD 0x25 must unpack to ASCII "25" in
| address order, and Musashi produces "52".
|
| Test: src byte = 0x25 at 0x00106001.  A1 = 0x00106002.
|   expand = ((0x25<<4)&0x0F00) | (0x25&0x0F) = 0x0200 | 0x0005 = 0x0205.
|   dst word = 0x0205 + 0x3030 = 0x3235.
|   Stored big-endian at (A0-2): 0x106010 = 0x32 ('2'), 0x106011 = 0x35
|   ('5') — i.e. ASCII "25" reading upward, as intended.
|   (Musashi would write 0x35 at 0x106010 and 0x32 at 0x106011 → "52".)

    .text
    .org 0

_start:
    lea     0x00106000, %a2
    move.l  #0xFFFFFFFF, (%a2)        | [106000..106003]
    move.l  #0xBADCAFE0, 16(%a2)      | [106010..106013]

    | src byte = 0x25 at 0x106001.
    move.b  #0x25, 1(%a2)

    lea     0x00106002, %a1
    lea     0x00106012, %a0

    | UNPK -(A1),-(A0),#$3030.
    | Opword: 1000_000_110001_001 = 0x8189.  ext1 = 0x3030.
    .word   0x8189, 0x3030            | UNPK -(A1),-(A0),#0x3030

    | A1 = 0x00106001 (predec by 1 only).
    move.l  %a1, %d0
    cmp.l   #0x00106001, %d0
    bne     _fail
    | A0 = 0x00106010 (predec by 2).
    move.l  %a0, %d1
    cmp.l   #0x00106010, %d1
    bne     _fail

    | Byte @ 0x00106010 = 0x32 ('2'); byte @ 0x00106011 = 0x35 ('5').
    move.b  (%a0), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x32, %d2
    bne     _fail
    move.b  1(%a0), %d3
    and.l   #0xFF, %d3
    cmp.l   #0x35, %d3
    bne     _fail

    | Adjacent byte @ 0x00106012 untouched = 0xAF.
    move.b  2(%a0), %d4
    and.l   #0xFF, %d4
    cmp.l   #0xAF, %d4
    bne     _fail

    | ── Corner: adj=0 → raw nibble split.  src=0x9F.
    | expand = (0x9F<<4)&0x0F00 | (0x9F&0x0F) = 0x0900 | 0x000F = 0x090F.
    | dst=0x090F + 0 = 0x090F.  byte @ (A0-2) = 0x09, @(A0-1) = 0x0F.
    move.b  #0x9F, 1(%a2)
    move.l  #0x11223344, 4(%a2)       | clear dst area at [106004..106007]
    lea     0x00106002, %a1
    lea     0x00106006, %a0

    .word   0x8189, 0x0000            | UNPK -(A1),-(A0),#0

    move.b  (%a0), %d5                | byte @ 0x106004 = 0x09
    and.l   #0xFF, %d5
    cmp.l   #0x09, %d5
    bne     _fail
    move.b  1(%a0), %d6                | byte @ 0x106005 = 0x0F
    and.l   #0xFF, %d6
    cmp.l   #0x0F, %d6
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
