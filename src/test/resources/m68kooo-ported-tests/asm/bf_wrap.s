| bf_wrap.s — 68020+ bitfield corners where offset+width crosses
| register boundary (offset+width > 32, wrap-around mask).
|
| offset=28, width=8 → field starts at bit (31-28)=3 and wraps around
| to bit 28.  Field bits (MSB first) = {bit3, bit2, bit1, bit0,
| bit31, bit30, bit29, bit28}.
|   mask_base = 0xFF000000  (top 8 bits set)
|   mask      = ROR_32(0xFF000000, 28) = 0xF000000F
|
| Pick d0 = 0xA000_000B = 1010_0000_..._0000_1011:
|   bit 31=1, 30=0, 29=1, 28=0, 3=1, 2=0, 1=1, 0=1
|   field bits = {3:1, 2:0, 1:1, 0:1, 31:1, 30:0, 29:1, 28:0}
|              = 1011_1010 = 0xBA
|   field MSB (= bit 3 of d0, landing at bit 31 of ROL(d0,28)) = 1 ⇒ N=1
|
|   BFEXTU: %d1 = 0xBA
|   BFEXTS: %d2 = sign-extend 0xBA in 8 bits (top bit=1) = 0xFFFFFFBA

    .text
    .org 0
_start:
    move.l  #0xA000000B, %d0

    | ---- BFTST with wrap: N=1, Z=0 ----
    bftst   %d0{28:8}
    bmi     1f
    bra     _fail
1:
    bne     2f                    | Z=0 because field non-zero
    bra     _fail
2:

    | ---- BFEXTU: %d1 = 0xBA ----
    bfextu  %d0{28:8}, %d1
    move.l  #0xBA, %d7
    cmp.l   %d7, %d1
    beq     3f
    bra     _fail
3:

    | ---- BFEXTS: %d2 = 0xFFFFFFBA (sign-extend 8-bit 0xBA) ----
    bfexts  %d0{28:8}, %d2
    move.l  #0xFFFFFFBA, %d7
    cmp.l   %d7, %d2
    beq     4f
    bra     _fail
4:

    | ---- BFCLR with wrap: clear bits 31..28 and 3..0 ----
    | d0 = 0xA000_000B → mask = 0xF000_000F.
    | Post BFCLR: d0 = 0xA000_000B & ~0xF000_000F
    |           = 0xA000_000B & 0x0FFF_FFF0 = 0x0000_0000.
    bfclr   %d0{28:8}
    tst.l   %d0
    beq     5f
    bra     _fail
5:

    | ---- BFSET with wrap on %d0 = 0 → d0 = 0xF000_000F ----
    moveq   #0, %d0
    bfset   %d0{28:8}
    move.l  #0xF000000F, %d7
    cmp.l   %d7, %d0
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
