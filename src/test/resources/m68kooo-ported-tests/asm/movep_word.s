| movep_word.s — MOVEP.W round-trip between Dn and strided memory.
|
| MOVEP.W stores 2 bytes (the low word of Dn) to (d16,An), (d16+2,An)
| in big-endian order (byte[15:8] to lower addr).  The inverse reads
| 2 bytes into the low word of Dn, preserving the high word.
|
| Byte-level effects are verified indirectly via WORD readbacks
| because the current decoder doesn't support arbitrary `move.b`
| addressing modes.  Strategy: pre-fill the 2 target words with a
| sentinel pattern, do MOVEP.W, then read each word back.  The
| untouched byte lanes should still carry the sentinel.
|
| PASS: sentinel 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a0
    lea     0x00020004, %a1    | scratch pointer to A0+4
    lea     0x00020006, %a2    | scratch pointer to A0+6
    lea     0x00020010, %a3    | source for MOVEP.W mem→reg
    lea     0x00020012, %a4    | second source byte

    | Pre-fill sentinel words at A0+4 and A0+6.
    move.w  #0xAA55, (%a1)
    move.w  #0xAA55, (%a2)

    move.l  #0xDEADBEEF, %d0
    movep.w %d0, 4(%a0)

    | Read back words at A0+4, A0+6 — expect 0xBE55 and 0xEF55.
    move.w  (%a1), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xBE55, %d2
    bne     _fail
    move.w  (%a2), %d2
    andi.l  #0xFFFF, %d2
    cmp.l   #0xEF55, %d2
    bne     _fail

    | Plant source bytes for mem→reg test.
    move.w  #0x1100, (%a3)    | byte at 0x10 = 0x11
    move.w  #0x2200, (%a4)    | byte at 0x12 = 0x22

    move.l  #0xCAFE9999, %d1
    movep.w 0x10(%a0), %d1
    cmp.l   #0xCAFE1122, %d1
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
