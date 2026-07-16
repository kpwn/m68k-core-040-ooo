| ext_extb.s — EXT.L and EXTB.L sign-extension tests
|
| Verifies:
|   EXT.L  Dn: sign-extends bits[15:0] into bits[31:0]
|   EXTB.L Dn: sign-extends bits[7:0]  into bits[31:0] (68020+)
|
| Test cases:
|   EXT.L  0x0000FFFF → 0xFFFFFFFF  (negative word)
|   EXT.L  0x00007FFF → 0x00007FFF  (positive word, unchanged)
|   EXTB.L 0x000000FF → 0xFFFFFFFF  (negative byte)
|   EXTB.L 0x0000007F → 0x0000007F  (positive byte, unchanged)
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000

    .text
    .org 0

_start:
    | ── EXT.L: 0xABCD8000 → 0xFFFF8000 (negative word) ──
    move.l  #0xABCD8000, %d0    | D0 = 0xABCD8000
    ext.l   %d0                 | D0 = 0xFFFF8000 (sign-extend word to long)
    move.l  #0xFFFF8000, %d1    | expected
    cmp.l   %d1, %d0
    bne     _fail               | wrong → FAIL

    | ── EXT.L: 0x12347FFF → 0x00007FFF (positive word) ──
    move.l  #0x12347FFF, %d0    | D0 has garbage in upper 16 bits
    ext.l   %d0                 | D0 = 0x00007FFF
    move.l  #0x00007FFF, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── EXTB.L: 0x00001280 → 0xFFFFFF80 (negative byte) ──
    move.l  #0x00001280, %d0    | D0 = 0x00001280
    extb.l  %d0                 | D0 = 0xFFFFFF80
    move.l  #0xFFFFFF80, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── EXTB.L: 0xABCD0042 → 0x00000042 (positive byte) ──
    move.l  #0xABCD0042, %d0
    extb.l  %d0                 | D0 = 0x00000042
    moveq   #0x42, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | ── EXT.L sets N=1 for negative result ──
    move.l  #0x0000FFFF, %d0
    ext.l   %d0                 | D0 = 0xFFFFFFFF, N=1
    bpl     _fail               | if N=0 → FAIL

    | ── EXT.L sets Z=1 for zero result ──
    move.l  #0xABCD0000, %d0
    ext.l   %d0                 | D0 = 0x00000000, Z=1
    bne     _fail               | if Z=0 → FAIL

    | ── PASS ──────────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS sentinel
_halt:
    stop    #0x2700
    bra     _halt

    | ── FAIL ──────────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)          | FAIL sentinel
_halt_fail:
    stop    #0x2700
    bra     _halt_fail
