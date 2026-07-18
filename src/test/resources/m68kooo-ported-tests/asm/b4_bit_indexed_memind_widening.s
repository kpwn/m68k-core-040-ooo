| b4_bit_indexed_memind_widening.s -- V2 widening corners for static
| bit-ops with brief-indexed (d8,An,Xn) and full-format memind dst.
|
| Hits corners not exercised by bit_static_indexed_mem.s or
| bit_ops_full_memind.s:
|   - BCHG.B (d8,A3,D1.W*1) — BCHG with brief-indexed dst (existing
|     bit_static_indexed_mem covers BTST/BCLR/BSET only).
|   - BCHG.B/BSET.B through ([bd.W,An],od.W) memind — RMW path with
|     byte-fused od.  Existing bit_ops_full_memind covers BTST/BCLR
|     ROM-frontier shapes; here we cover BCHG with non-zero bit number
|     and BSET through a different An base.
|
| PASS: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | --- BCHG #5,(8,A3,D1.W*1) — toggle a clear bit at 8+16=24 byte.
    lea     0x0011b000, %a3
    move.l  #0x00000010, %d1
    lea     0x0011b018, %a0
    move.b  #0x00, (%a0)            | start clear at d8=8 + Xn=16 = 24
    .word   0x0873, 0x0005, 0x1008  | bchg #5,(8,A3,D1.W*1)

    | wait for store to drain
    moveq   #16, %d7
1:  subq.l  #1, %d7
    bne     1b

    bne     _fail1                  | Z=0 means old bit was set; we
                                    | started cleared, so Z must be 1.
    move.b  (%a0), %d0
    cmp.b   #0x20, %d0              | bit 5 set: 0x20
    bne     _fail1

    | --- BCHG #2,([bd.W,A2],od.W) — pre-indexed memind, BS=0, IS=1,
    | ---   BD.W=0, OD.W=4.
    lea     0x0011c000, %a2
    move.l  #0x0011c100, (%a2)
    lea     0x0011c104, %a4
    move.b  #0x10, (%a4)            | bit 2 clear in 0x10
    .word   0x0872, 0x0002, 0x0162, 0x0000, 0x0004  | bchg #2,([0,A2],4)

    moveq   #16, %d7
2:  subq.l  #1, %d7
    bne     2b
    bne     _fail2                  | bit was 0 -> Z=1 expected
    move.b  (%a4), %d3
    cmp.b   #0x14, %d3              | 0x10 ^ 0x04 = 0x14
    bne     _fail2

    | --- BSET #6,([bd.W,A2],od.null) — preindexed, NULL od, BS=0.
    lea     0x0011d000, %a2
    move.l  #0x0011d200, (%a2)
    lea     0x0011d200, %a5
    move.b  #0x00, (%a5)
    .word   0x08f2, 0x0006, 0x0161, 0x0000  | bset #6,([0,A2],null)

    moveq   #16, %d7
3:  subq.l  #1, %d7
    bne     3b
    bne     _fail3                  | bit was 0 -> Z=1 expected
    move.b  (%a5), %d4
    cmp.b   #0x40, %d4              | bit 6 set -> 0x40
    bne     _fail3

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7

_fail:
    lea     0xFFFF0000, %a6
    move.l  %d7, (%a6)
_halt_fail:
    bra     _halt_fail
