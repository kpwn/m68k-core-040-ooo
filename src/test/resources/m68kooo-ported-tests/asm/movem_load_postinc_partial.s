| movem_load_postinc_partial.s — MOVEM.L (A0)+, D0/D3/A1/A5
|
| Stage D-9a V2 corner: postinc LOAD with a sparse register list.  The
| assembler must walk the mask bits in NORMAL order (bit 0 = D0 ..
| bit 15 = A7) and update A0 at the END by +4*popcount(mask) = +16.
|
| Only the NAMED registers should receive loaded values; other registers
| must be untouched.
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7              | stack base

    | Build an in-memory source image at 0x00020000.  4 longs, little-
    | semantically (they'll be read via LOAD.L in big-endian order).
    lea     0x00020000, %a0
    move.l  #0xDEAD0000, (%a0)
    move.l  #0xDEAD0003, 4(%a0)
    move.l  #0xDEADA001, 8(%a0)
    move.l  #0xDEADA005, 12(%a0)
    | Add a sentinel long at 16 bytes that MUST NOT be loaded — if the
    | postinc-update misfires, A0 will land somewhere wrong and later
    | accesses will read the wrong bytes.
    move.l  #0xCAFEBABE, 16(%a0)

    | Prime targets with recognisable "untouched" canaries.
    move.l  #0xBEFACE00, %d0
    move.l  #0xBEFACE01, %d1
    move.l  #0xBEFACE02, %d2
    move.l  #0xBEFACE03, %d3
    move.l  #0xBEFACE04, %d4
    move.l  #0xBEFACE05, %d5
    move.l  #0xBEFACE06, %d6
    move.l  #0xBEFACE07, %d7
    move.l  #0xBEFA0001, %a1
    move.l  #0xBEFA0003, %a2
    move.l  #0xBEFA0004, %a3
    move.l  #0xBEFA0004, %a4
    move.l  #0xBEFA0005, %a5
    move.l  #0xBEFA0006, %a6

    | Load four regs — mask = bits {0=D0, 3=D3, 9=A1, 13=A5} = 0x2209.
    movem.l (%a0)+, %d0/%d3/%a1/%a5

    | Selected registers must hold loaded values.
    cmp.l   #0xDEAD0000, %d0
    bne     _fail
    cmp.l   #0xDEAD0003, %d3
    bne     _fail
    move.l  %a1, %d2
    cmp.l   #0xDEADA001, %d2
    bne     _fail
    move.l  %a5, %d2
    cmp.l   #0xDEADA005, %d2
    bne     _fail

    | Untouched registers must retain their canary.
    cmp.l   #0xBEFACE01, %d1
    bne     _fail
    cmp.l   #0xBEFACE06, %d6
    bne     _fail

    | A0 must have advanced by 4*4 = 16 bytes → 0x00020010.
    move.l  %a0, %d2
    cmp.l   #0x00020010, %d2
    bne     _fail

    | For good measure, read the sentinel via the updated A0 — if post-
    | inc landed anywhere else, this fails:
    move.l  (%a0), %d2
    cmp.l   #0xCAFEBABE, %d2
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
