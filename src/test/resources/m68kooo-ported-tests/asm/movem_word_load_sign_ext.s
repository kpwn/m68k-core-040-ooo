| movem_word_load_sign_ext.s — MOVEM.W (A0)+, D0/D1
|
| Stage D-9a V2 corner: MOVEM.W LOAD must SIGN-EXTEND each loaded 16-bit
| word to 32 bits per PRM §4.79.  The LSU zero-extends .W loads; the
| assembler follows each LOAD.W phase with an ALU_EXT phase to fix
| bits [31:16].  This test reads two negative half-words and verifies
| the sign propagated.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | stack base

    | Source image: two 16-bit negative values at 0x00020000 and 0x00020002.
    |   word @ 0x00020000 : 0x8001  →  expected D0 = 0xFFFF8001
    |   word @ 0x00020002 : 0xFFFE  →  expected D1 = 0xFFFFFFFE
    lea     0x00020000, %a0
    move.w  #0x8001, (%a0)
    move.w  #0xFFFE, 2(%a0)

    | Prime D0 and D1 with non-zero upper halves so we can see the sign-
    | extension actually happened (as opposed to plain-load leaving
    | bits [31:16] = 0 which would pass a plain zero-extend check).
    move.l  #0x11110000, %d0
    move.l  #0x22220000, %d1

    movem.w (%a0)+, %d0/%d1

    cmp.l   #0xFFFF8001, %d0
    bne     _fail
    cmp.l   #0xFFFFFFFE, %d1
    bne     _fail

    | A0 must have advanced by 2*2 = 4 bytes (stride=2 for .W).
    move.l  %a0, %d2
    cmp.l   #0x00020004, %d2
    bne     _fail

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
