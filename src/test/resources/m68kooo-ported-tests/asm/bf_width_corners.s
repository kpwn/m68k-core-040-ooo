| bf_width_corners.s — BFEXTU/BFEXTS/BFTST with edge-case widths.
|
| Tests:
|   width=1 (smallest valid)
|   width=32 (encoded as raw-0, all of register)
|   offset=0, width=1  → bit 31 of d0
|   offset=31, width=1 → bit 0 of d0
|
| Ensures the width-raw=0 → width-eff=32 mapping works, and
| single-bit field extraction + sign-extension is correct.

    .text
    .org 0
_start:
    | ---- BFEXTU width=1, offset=0: %d1 = d0[31] ----
    move.l  #0x80000000, %d0
    bfextu  %d0{0:1}, %d1
    moveq   #1, %d7
    cmp.l   %d7, %d1
    beq     1f
    bra     _fail
1:

    | BFEXTS width=1, offset=0: %d2 = sign-extend d0[31] = -1
    bfexts  %d0{0:1}, %d2
    move.l  #0xFFFFFFFF, %d7
    cmp.l   %d7, %d2
    beq     2f
    bra     _fail
2:

    | ---- width=1, offset=31: %d1 = d0[0] ----
    move.l  #0x00000001, %d0
    bfextu  %d0{31:1}, %d1
    moveq   #1, %d7
    cmp.l   %d7, %d1
    beq     3f
    bra     _fail
3:

    | BFEXTS width=1, offset=31: %d2 = sign-extend d0[0] = -1
    bfexts  %d0{31:1}, %d2
    move.l  #0xFFFFFFFF, %d7
    cmp.l   %d7, %d2
    beq     4f
    bra     _fail
4:

    | ---- BFTST width=1, offset=0: Z depends on d0[31] ----
    moveq   #0, %d0
    bftst   %d0{0:1}
    beq     5f                     | Z=1 because d0[31]=0
    bra     _fail
5:

    | ---- BFEXTU width=32, offset=0: %d1 = d0 (identity) ----
    move.l  #0x5A5A5A5A, %d0
    bfextu  %d0{0:32}, %d1
    cmp.l   %d0, %d1
    beq     6f
    bra     _fail
6:

    | ---- BFFFO width=1 on a zero bit → result = offset + 1 ----
    moveq   #0, %d0
    bfffo   %d0{4:1}, %d1
    moveq   #5, %d7                | offset(4) + width(1) - 0_found = 5
    cmp.l   %d7, %d1
    beq     7f
    bra     _fail
7:

    | ---- BFFFO width=1 on a set bit → result = offset ----
    move.l  #0x08000000, %d0       | bit 27 set
    bfffo   %d0{4:1}, %d1
    moveq   #4, %d7
    cmp.l   %d7, %d1
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
