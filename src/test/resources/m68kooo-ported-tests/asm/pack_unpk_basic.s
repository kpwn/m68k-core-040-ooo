| pack_unpk_basic.s — PACK / UNPK reg-reg sanity
|
| PACK Dy,Dx,#imm16:
|   sum = (Dy[15:0] + imm16) & 0xFFFF
|   Dx[7:0] = ((sum >> 4) & 0xF0) | (sum & 0x0F)
|   Upper 24 bits of Dx preserved.  CCR untouched.
|
| UNPK Dy,Dx,#imm16:
|   expand = ((Dy[7:0] << 4) & 0x0F00) | (Dy[7:0] & 0x000F)
|   Dx[15:0] = (expand + imm16) & 0xFFFF
|   Upper 16 bits of Dx preserved.  CCR untouched.
|
| Common use: ASCII-BCD conversion.
|   PACK "12" (0x3132) with imm=0xFF9F (or 0xFFFE per PRM examples)
|        actually the adjust ASCII '0'=0x30 → strip via nibble pack:
|        ((0x3132 - 0x3030) >> 4) & 0xF0 | ... = pack 0x12.
|   UNPK 0x12 with imm=0x3030 → 0x3132 ("12" ASCII).

    .text
    .org 0

_start:
    | ── UNPK 0x12 → 0x3132 ASCII "12" ──
    | Seed Dy=0x00000012, Dx=0xDEAD0000 (upper 16 preserved).
    move.l  #0x00000012, %d1
    move.l  #0xDEAD0000, %d0
    unpk    %d1, %d0, #0x3030         | D0[15:0] = (0x0102 + 0x3030) = 0x3132
    move.l  #0xDEAD3132, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── UNPK 0x9F → 0x3930 + 0x003F = mix ──
    | expand(0x9F) = 0x090F; + 0x0000 = 0x090F.
    move.l  #0x0000009F, %d1
    move.l  #0x12340000, %d0
    unpk    %d1, %d0, #0x0000
    move.l  #0x1234090F, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── PACK "12" (ASCII 0x3132) + imm=0xCFD0 → 0x0102 → byte 0x12 ──
    | Actually the canonical pack-ASCII-to-BCD is: sum = Dy + imm =
    | 0x3132 + 0xCFD0 = 0x10102 (wraps to 0x0102).
    | res = ((0x0102 >> 4) & 0xF0) | (0x0102 & 0x0F) = 0x10 | 0x02 = 0x12.
    move.l  #0x00003132, %d1
    move.l  #0xCAFEBE00, %d0
    pack    %d1, %d0, #0xCFD0
    move.l  #0xCAFEBE12, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── PACK 0x0000 + imm=0x0000 → 0x00 ──
    move.l  #0x00000000, %d1
    move.l  #0x11223300, %d0
    pack    %d1, %d0, #0x0000
    move.l  #0x11223300, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── PACK arbitrary: Dy=0x5678, imm=0x1000 → sum=0x6678 →
    |    res = ((0x6678 >> 4) & 0xF0) | (0x6678 & 0x0F) = 0x60 | 0x08 = 0x68 ──
    move.l  #0x00005678, %d1
    move.l  #0x77788800, %d0
    pack    %d1, %d0, #0x1000
    move.l  #0x77788868, %d7
    cmp.l   %d7, %d0
    bne     _fail

    | ── UNPK arbitrary: Dy=0x68, imm=0x0000 → expand=0x0608, result Dx[15:0]=0x0608 ──
    move.l  #0x00000068, %d1
    move.l  #0x99AA0000, %d0
    unpk    %d1, %d0, #0x0000
    move.l  #0x99AA0608, %d7
    cmp.l   %d7, %d0
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
