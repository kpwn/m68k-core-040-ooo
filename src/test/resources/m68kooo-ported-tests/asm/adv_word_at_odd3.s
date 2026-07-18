| adv_word_at_odd3.s — WORD access straddling a LONG-aligned boundary
|
| ASSUMPTION TESTED (lsu.v:372):
|   The straddles_long() function returns 0 for SZ_WORD when ea[1:0]==3.
|   Comment: "FIXME: ea[1:0]==11" — "vanishingly rare in compiler output
|   and currently aliases to the aligned-down read in the existing
|   single-beat path".
|
| ATTACK:
|   Write a 16-bit value 0xBEEF at byte addresses 0x10003..0x10004 (a
|   WORD that crosses the LONG boundary at 0x10004).  Then read it back.
|
|   If straddles_long ignores word-at-odd-LONG-boundary, the LSU will
|   issue a single-beat aligned-down request at 0x10000 with a strb
|   that tries to write byte 3 only, losing the other half. The read
|   will also single-beat, getting only half the data.
|
| FAILURE MODE:
|   Either the store writes to the wrong addresses (half lost to 0x10000
|   instead of 0x10004), OR the load returns a different word than was
|   stored, OR Musashi disagrees with the RTL on the final register value.
|
| WHAT A DIVERGENCE INDICATES:
|   Unaligned WORD access at 4-byte boundary is silently miscomputing —
|   will fail on any real code that stacks a WORD starting one-byte before
|   a LONG boundary.

    .text
    .org 0

_start:
    lea     0x00020000, %a7         | SSP well away from test area

    | Store 0xBEEF at bytes [0x10003..0x10004] — straddles LONG boundary 0x10004
    lea     0x00010003, %a0         | A0 = 0x00010003
    move.w  #0xBEEF, (%a0)          | WORD store: bytes 3@0x10003 + 4@0x10004

    | Force re-read from memory (not forwarding): use a fresh address
    lea     0x00010003, %a1
    move.w  #0x0000, %d1
    move.w  (%a1), %d1              | D1.W should be 0xBEEF

    | Compare: D1.W must match what we stored
    move.l  #0x0000BEEF, %d7
    cmp.l   %d7, %d1
    bne     _fail

_pass:
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
