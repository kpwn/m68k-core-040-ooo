| bf_basic.s — 68020+ bitfield ops, reg-direct EA, full-reg case (offset=0,
| width=32).  Exercises BFTST / BFEXTU / BFEXTS / BFCLR / BFSET / BFCHG on
| Dn with the entire register as the field.  Verifies the op is a no-op
| plus flags (TST), an identity extract (EXTU/EXTS), all-zeros (CLR),
| all-ones (SET), and bitwise NOT (CHG) — i.e. the trivial-mask case.
|
| PASS sentinel:  0xC0FFEE00 → 0xFFFF0000 (expected by tb_top.cpp).
|
| Covers:
|   BFTST  %d0{0:32} — N/Z reflect current %d0
|   BFEXTU %d0{0:32}, %d1 — %d1 == %d0
|   BFEXTS %d0{0:32}, %d1 — %d1 == %d0 (sign-extend of the full 32 bits
|                                       is identity in a 32-bit Dn)
|   BFCLR  %d0{0:32} — %d0 == 0
|   BFSET  %d0{0:32} — %d0 == 0xFFFFFFFF
|   BFCHG  %d0{0:32} — %d0 XOR 0xFFFFFFFF (bitwise NOT)

    .text
    .org 0
_start:
    | ---- BFTST full-reg: %d0 = 0x80000000 → N=1, Z=0 ----
    move.l  #0x80000000, %d0
    bftst   %d0{0:32}
    bmi     1f                      | N must be 1 (MSB of d0 = 1)
    bra     _fail
1:
    bne     2f                      | Z must be 0 (nonzero)
    bra     _fail
2:

    | ---- BFTST full-reg: %d0 = 0 → Z=1, N=0 ----
    moveq   #0, %d0
    bftst   %d0{0:32}
    beq     3f                      | Z must be 1
    bra     _fail
3:
    bpl     4f                      | N must be 0 (d0[31] = 0)
    bra     _fail
4:

    | ---- BFEXTU full-reg: %d1 = %d0 ----
    move.l  #0xDEADBEEF, %d0
    bfextu  %d0{0:32}, %d1
    cmp.l   %d0, %d1
    beq     5f
    bra     _fail
5:

    | ---- BFEXTS full-reg: %d2 = %d0 (identity in 32-bit container) ----
    bfexts  %d0{0:32}, %d2
    cmp.l   %d0, %d2
    beq     6f
    bra     _fail
6:

    | ---- BFCLR full-reg: %d0 = 0 ----
    bfclr   %d0{0:32}
    tst.l   %d0
    beq     7f
    bra     _fail
7:

    | ---- BFSET full-reg: %d0 = 0xFFFFFFFF ----
    bfset   %d0{0:32}
    move.l  #0xFFFFFFFF, %d3
    cmp.l   %d3, %d0
    beq     8f
    bra     _fail
8:

    | ---- BFCHG full-reg: %d0 flips every bit ----
    move.l  #0x12345678, %d0
    bfchg   %d0{0:32}
    move.l  #0xEDCBA987, %d4       | ~0x12345678
    cmp.l   %d4, %d0
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
