| pack_mem_mem.s — PACK -(A1),-(A0),#0
|
| PRM §4.146: "the source operand is a 16-bit word" read through -(Ay);
| the adjustment is added; bits [11:8] and [3:0] of the sum are
| concatenated into a byte written through -(Ax).
| On a big-endian machine the source word's MSB is the LOWER-address
| byte, so the source is exactly one LOAD.W at (A1-2).
|
| ── HEADER CORRECTION (2026-08-09) ────────────────────────────────
| The previous revision of this comment claimed Musashi shifts the
| SECOND read left by 8.  That is backwards, and it made the test look
| like it agreed with Musashi when it does not.  Musashi's actual
| m68k_in.c `pack, 16, mm, .` is:
|     ea = EA_AY_PD_8(); src = read_8(ea);           <- HIGHER address
|     ea = EA_AY_PD_8(); src = (src << 8) | read_8(ea);
| i.e. the FIRST read — the one at the HIGHER address — becomes the
| MSB.  That is byte-swapped relative to a big-endian word read.
|
| We follow the PRM, not Musashi, and this is a KNOWN DELIBERATE
| DEVIATION (see docs/isa_status.md).  The PRM reading is also the only
| one that makes PACK do its documented job: ASCII "12" stored in
| address order (0x31,0x32) must pack to 0x12.  Musashi's own source
| corroborates the bug independently — its A7 paths call EA_A7_PD_8()
| (A7 -= 2) twice on the WORD side, moving A7 by 4 where a word access
| moves it by 2.
|
| Test: byte 0x32 at 0x106000, byte 0x31 at 0x106001; A1 = 0x00106002.
|       Big-endian source word at 0x00106000 = 0x3231.
|       adj = 0, so sum = 0x3231.
|       Packed = ((sum>>4)&0xF0) | (sum&0x0F)
|              = (0x0323 & 0xF0) | 0x01 = 0x20 | 0x01 = 0x21.
|       Write 0x21 to (A0-1).
|       (Musashi, byte-swapped, would build src=0x3132 and yield 0x12.)

    .text
    .org 0

_start:
    lea     0x00106000, %a2           | scratch base
    move.l  #0xFFFFFFFF, (%a2)        | [106000..106003] = FF FF FF FF
    move.l  #0xBADCAFE0, 16(%a2)      | [106010..106013] = BA DC AF E0

    | Seed src bytes: 0x32 at 106000, 0x31 at 106001.
    move.b  #0x32, 0(%a2)
    move.b  #0x31, 1(%a2)

    lea     0x00106002, %a1
    lea     0x00106011, %a0           | dst predec target = 0x106010

    | PACK -(A1),-(A0),#0.  Opword 1000_000_101001_001 + ext1 = 0x0000.
    | Dx = A0 (opword[11:9]=0), Dy = A1 (opword[2:0]=1).
    | 1000 000 101001 001 = 0x8149.
    .word   0x8149, 0x0000            | PACK -(A1),-(A0),#0

    | A1 should be 0x00106000, A0 should be 0x00106010.
    move.l  %a1, %d0
    cmp.l   #0x00106000, %d0
    bne     _fail
    move.l  %a0, %d1
    cmp.l   #0x00106010, %d1
    bne     _fail

    | Byte @ 0x00106010 = 0x21.
    move.b  (%a0), %d2
    and.l   #0xFF, %d2
    cmp.l   #0x21, %d2
    bne     _fail

    | Adjacent byte at 0x00106011 must still be 0xDC.
    move.b  1(%a0), %d3
    and.l   #0xFF, %d3
    cmp.l   #0xDC, %d3
    bne     _fail

    | ── Non-zero adj, exercising 16-bit wraparound of the add ──────
    | Bytes 0x37 at 0x106000, 0x39 at 0x106001 → big-endian source
    | word 0x3739.  adj = 0xFFCA.
    | 0x3739 + 0xFFCA = 0x13703, truncated to 16 bits = 0x3703.
    | Packed = ((0x3703>>4)&0xF0) | (0x3703&0x0F)
    |        = (0x0370 & 0xF0) | 0x03 = 0x70 | 0x03 = 0x73.
    | This verifies the add-then-pack chain and the carry-out discard.
    | (Musashi, byte-swapped, would build 0x3937 and yield 0x91.)
    move.b  #0x37, 0(%a2)             | 0x106000 = 0x37 (high byte)
    move.b  #0x39, 1(%a2)             | 0x106001 = 0x39 (low byte)
    move.l  #0xBADCAFE0, 16(%a2)      | restore 0x106010..106013
    lea     0x00106002, %a1
    lea     0x00106011, %a0
    .word   0x8149, 0xFFCA            | PACK -(A1),-(A0),#0xFFCA

    move.b  (%a0), %d4
    and.l   #0xFF, %d4
    cmp.l   #0x73, %d4
    bne     _fail

    | A-register updates.
    move.l  %a1, %d5
    cmp.l   #0x00106000, %d5
    bne     _fail
    move.l  %a0, %d6
    cmp.l   #0x00106010, %d6
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
