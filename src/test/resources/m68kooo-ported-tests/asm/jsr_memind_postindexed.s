| jsr_memind_postindexed.s — JSR full-format memory-indirect, POST-indexed
| (I/IS = 1xx).
|
| Pre-indexed  ([bd,An,Xn*sc],od) : EA = mem[bd + An + Xn*sc] + od
| Post-indexed ([bd,An],Xn*sc,od) : EA = mem[bd + An] + Xn*sc + od
|
| decode_0100.vh gates all four JSR/JMP mode-6 memory-indirect sites on
|     (ext1[3:0] == 4'b0001 || 4'b0010 || 4'b0011)
| i.e. pre-indexed only, so post-indexed falls through to a catch-all that
| mis-decodes rather than trapping.  This form is present in real System
| 7.5.3 code: the identical opword+extension pair appears in BOTH the FPGA
| and MAME 8 MiB RAM images at analogous relocated addresses
| (0x152672 / 0x15db72 = 4eb0 2f2e), so it is genuine code, not a
| coincidental byte pattern.
|
| Encoding used here:
|   4EB0 2515 = JSR ([A0], D2.W*4)
|     4EB0 : JSR, mode 110 (d8,An,Xn), reg 000 = A0
|     2515 : D/A=0 reg=D2 W/L=word scale=4 full=1 BS=0 IS=0
|            BD SIZE=01 (null)  I/IS=101 (POST-indexed, null outer disp)
|   EA = mem[A0] + D2.W*4, total length 4 bytes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL: 0xDEADnnnn in D7.

    .text
    .org 0

_start:
    lea     0x00080000, %a7
    moveq   #0, %d0

    | mem[A0] = _sub_y - 16, D2 = 4 -> EA = (_sub_y - 16) + 16 = _sub_y.
    | The post-index add is therefore load-bearing: if the RTL treats this
    | as PRE-indexed it computes mem[A0 + 16] instead, which is a
    | different (unwritten) slot.
    lea     0x00030000, %a0
    move.l  #(_sub_y - 16), (%a0)
    moveq   #4, %d2

    .word   0x4EB0, 0x2515

    cmp.l   #0x00000042, %d0
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

    | 16 bytes of padding so that (_sub_y - 16) is a valid, distinct
    | address that is NOT itself the entry point.
    .space  16, 0

_sub_y:
    move.l  #0x00000042, %d0
    rts
