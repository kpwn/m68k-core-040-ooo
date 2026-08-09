| moveb_preserves_upper_24.s -- MOVE.B <ea>,Dn must PRESERVE bits 31:8
|
| Why this exists: the System 7.5.3 handle-size hang traces to D1 = 0xFFFFFFE8
| (-24) reaching an UNSIGNED size comparison. The ROM block-mover at
| 0x4080EC0E produces that D1 with
|       moveb %a2@,%d1
| and on 68k MOVE.B writes ONLY bits 7:0 -- bits 31:8 keep their prior value.
| So 0xFFFFFFE8 is exactly "stale upper 24 bits 0xFFFFFF" + loaded byte 0xE8.
| If our CPU zero- or sign-extended instead of preserving, we would produce a
| different D1 from the golden model, which is the observed divergence.
|
| The fuzz corpus emits MOVE.B->Dn, but preservation is only exercised if the
| destination holds a NONZERO value first -- the same artificially-narrow-bound
| shape that hid the MOVEM register-list bug. This pins it explicitly.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
    .equ SENT, 0xFFFF0000

_start:
    lea     src, %a2

    | case 1: upper bits all ones, byte 0xE8  -> expect 0xFFFFFFE8 (-24)
    move.l  #0xFFFFFF00, %d1
    move.b  (%a2), %d1
    cmp.l   #0xFFFFFFE8, %d1
    bne     fail

    | case 2: upper bits a distinctive pattern, byte 0x00 -> expect 0xA5A5A500
    move.l  #0xA5A5A5A5, %d1
    move.b  1(%a2), %d1
    cmp.l   #0xA5A5A500, %d1
    bne     fail

    | case 3: byte with bit7 set must NOT sign-extend into the upper bits
    move.l  #0x00000000, %d1
    move.b  (%a2), %d1
    cmp.l   #0x000000E8, %d1
    bne     fail

    | case 4: same, via a data-register source (MOVE.B Dm,Dn)
    move.l  #0xFFFFFF00, %d2
    move.l  #0x12345678, %d3
    move.b  %d3, %d2
    cmp.l   #0xFFFFFF78, %d2
    bne     fail

pass:
    move.l  #0xC0FFEE00, %d0
    move.l  #SENT, %a0
    move.l  %d0, (%a0)
    bra     .
fail:
    move.l  #0xDEAD0001, %d0
    move.l  #SENT, %a0
    move.l  %d0, (%a0)
    bra     .

    .align 2
src:
    .byte   0xE8
    .byte   0x00
    .byte   0x00
    .byte   0x00
