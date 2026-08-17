| jsr_memind_long_bd.s — JSR full-format memory-indirect with a LONG
| base displacement (BD SIZE = 2'b11).
|
| decode.v:478 computes
|     jsr_full_memind_bd_w = (ext1[5:4] == 2'b10)      | WORD bd only
|     jsr_full_memind_len  = 4 + (bd_w ? 2 : 0) + (od_w ? 2 : 0)
| so BD SIZE = 2'b11 (long) silently degrades to "null bd, length 4":
| both the EA and the instruction length are wrong, and the 4 bd bytes
| get decoded as the next instruction.  The sibling LEA path
| (lea_full_indexed_bd_l) does handle long bd, so this is an omission,
| not a deliberate restriction.  cpu/CLAUDE.md lists JSR/JMP full-format
| memory-indirect as a known deferred surface.
|
| Encoding used here:
|   4EB0 2531 0000 0400  = JSR ([$400 + A0 + D2.W*4])
|     4EB0 : JSR, mode 110 (d8,An,Xn), reg 000 = A0
|     2531 : D/A=0 reg=D2 W/L=word scale=4 full=1 BS=0 IS=0
|            BD SIZE=11 (long)  I/IS=001 (preindexed, null outer disp)
|   total length 8 bytes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADnnnn in D7.

    .text
    .org 0

_start:
    lea     0x00080000, %a7
    moveq   #0, %d0

    | Pointer slot: bd(0x400) + A0(0x00030000) + D2.W*4(0x10) = 0x00030410
    lea     0x00030000, %a0
    lea     0x00030410, %a1
    move.l  #_sub_x, (%a1)
    moveq   #4, %d2

    .word   0x4EB0, 0x2531
    .long   0x00000400

    | _sub_x sets D0 = 0x11.  If the length were mis-decoded the 4 bd
    | bytes would execute as an instruction and we would not get here
    | with D0 == 0x11.
    cmp.l   #0x00000011, %d0
    bne     _fail1
    cmpa.l  #0x00080000, %a7
    bne     _fail2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1: move.l #0xDEAD0001, %d7
    bra _fail
_fail2: move.l #0xDEAD0002, %d7
    bra _fail
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_fail_halt:
    bra     _fail_halt

_sub_x:
    move.l  #0x00000011, %d0
    rts
