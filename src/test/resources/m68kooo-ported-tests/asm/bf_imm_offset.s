| bf_imm_offset.s — 68020+ bitfield with static offset=5, width=7 on Dn.
| Picks semi-random data and verifies the exact extracted / modified bits
| against golden constants computed offline per Musashi semantics.
|
| Layout of a %d0 = 0x89ABCDEF for offset=5, width=7:
|   bit index (MSB=31):      31 30 29 28 27 26 25 24 23 22 21 20
|   value of %d0[31:20]:     1  0  0  0  1  0  0  1  1  0  1  0
|   field (offset=5, width=7): bits 26..20
|   bits 26,25,24,23,22,21,20 = 0,0,1,1,0,1,0 → binary 0011010 = 0x1A
|   top bit of field (bit 26) = 0  ⇒ N = 0
|   field != 0                   ⇒ Z = 0
|   BFEXTU result = 0x1A
|   BFEXTS result = sign-extend from bit 6 (MSB of the 7-bit field) = 0  ⇒ 0x1A
|
| Covers: BFTST / BFEXTU / BFEXTS on non-trivial mask.

    .text
    .org 0
_start:
    move.l  #0x89ABCDEF, %d0

    | ---- BFTST: expect N=0, Z=0 ----
    bftst   %d0{5:7}
    bpl     1f                       | N should be 0
    bra     _fail
1:
    bne     2f                       | Z should be 0 (field nonzero)
    bra     _fail
2:

    | ---- BFEXTU: %d1 = 0x1A ----
    bfextu  %d0{5:7}, %d1
    move.l  #0x1A, %d7
    cmp.l   %d7, %d1
    beq     3f
    bra     _fail
3:

    | ---- BFEXTS: %d2 = 0x1A (MSB of field = 0, no sign-ext) ----
    bfexts  %d0{5:7}, %d2
    move.l  #0x1A, %d7
    cmp.l   %d7, %d2
    beq     4f
    bra     _fail
4:

    | ---- Pick data where field MSB = 1 to exercise sign-extend + N flag ----
    | d0 = 0x04000000 → bit 26 = 1, all other field bits = 0.
    | offset=5, width=7 → field bits 26..20 = 1000000 = 0x40
    |   BFEXTU result = 0x40
    |   BFEXTS result = sign-extend "1000000" (top bit = 1) → 0xFFFFFFC0
    |   BFTST N = 1, Z = 0.
    move.l  #0x04000000, %d0
    bftst   %d0{5:7}
    bmi     5f                       | N must be 1
    bra     _fail
5:
    bne     6f                       | Z must be 0
    bra     _fail
6:
    bfextu  %d0{5:7}, %d1
    move.l  #0x40, %d7
    cmp.l   %d7, %d1
    beq     7f
    bra     _fail
7:
    bfexts  %d0{5:7}, %d2
    move.l  #0xFFFFFFC0, %d7
    cmp.l   %d7, %d2
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
