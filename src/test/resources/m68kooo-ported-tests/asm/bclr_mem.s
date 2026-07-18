| bclr_mem.s — BCLR/BSET/BCHG #n,(d16,An) memory bit-ops must read-modify-
| write the byte correctly.
|
| Wedge hypothesis: the Q700 ROM at 0x4080a966 does `bclr #5,(0x15d,A3)`
| to clear the device-search "in progress" flag.  If a static memory
| BCLR doesn't write the cleared byte back, the flag stays set and the
| boot wedges spinning `btst #5,(0x15d,A3)/bne` at 0x4080a8e6.
|
| Memory bit-ops are BYTE-sized; bit number 0..7; Z = the OLD bit value.
|
| PASS: 0xC0FFEE00.
| FAIL:
|   0xDEAD0004  vec-4 illegal trap
|   0xDEAD0B C1  BCLR #5 left wrong byte (0x24 -> expect 0x04)
|   0xDEAD0BC2  BSET #3 wrong (-> expect 0x0C)
|   0xDEAD0BC3  BCHG #2 wrong (-> expect 0x08)
|   0xDEAD0BC4  BTST #3 flag wrong

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010

    move.l  #0x00020000, %a0
    move.b  #0x24, 0x10(%a0)            | mem.B[0x20010] = 0x24 (bit5+bit2)

    | ── BCLR #5,(d16,An) → clear bit 5 → 0x04 ──
    bclr    #5, 0x10(%a0)
    move.b  0x10(%a0), %d0
    and.w   #0xFF, %d0
    cmp.w   #0x04, %d0
    bne     _f1

    | ── BSET #3,(d16,An) → set bit 3 → 0x0C ──
    bset    #3, 0x10(%a0)
    move.b  0x10(%a0), %d0
    and.w   #0xFF, %d0
    cmp.w   #0x0C, %d0
    bne     _f2

    | ── BCHG #2,(d16,An) → toggle bit 2 → 0x08 ──
    bchg    #2, 0x10(%a0)
    move.b  0x10(%a0), %d0
    and.w   #0xFF, %d0
    cmp.w   #0x08, %d0
    bne     _f3

    | ── BTST #3,(d16,An) → bit 3 set → Z=0 ──
    btst    #3, 0x10(%a0)
    beq     _f4

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_f1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0BC1, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_f2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0BC2, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_f3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0BC3, %d0
    move.l  %d0, (%a0)
_h3:
    bra     _h3

_f4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0BC4, %d0
    move.l  %d0, (%a0)
_h4:
    bra     _h4

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_h7:
    bra     _h7
