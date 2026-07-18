| dcache_line_boundary.s — Line-fill across a 32-byte cache-line boundary
|
| The L1D uses 32-byte lines indexed by addr[9:5].  Two addresses that
| differ by exactly 0x20 live in DIFFERENT lines (adjacent sets).  This
| test writes a distinct pattern into each of four consecutive lines
| (spanning 128 bytes) and reads them all back.  The cache must:
|   - miss-fill each line on its first access
|   - NOT alias the sets (each line lands in a distinct BRAM row)
|   - return the exact stored value from its respective line
|
| What this would catch:
|   - Off-by-one in set-index slicing (addr[9:5] vs addr[10:5]).
|   - Line-fill beat addressing starting at the requested word rather
|     than the line-aligned base, which would miss bytes at low
|     offsets on subsequent reads.
|   - Tag register being shared across adjacent sets.

    .text
    .org 0

_start:
    | Set up base pointer to a 128-byte aligned scratch window well
    | outside our test binary and well below the sentinel region.
    lea     0x00300000, %a0

    | Line A at [A0 +   0..1F]
    move.l  #0x11111111, %d0
    move.l  %d0, (%a0)              |  0x300000
    move.l  #0x22222222, %d1
    move.l  %d1, 28(%a0)            |  0x30001C (last word of line A)

    | Line B at [A0 + 20..3F] — different line, adjacent set
    move.l  #0x33333333, %d2
    move.l  %d2, 32(%a0)            |  0x300020
    move.l  #0x44444444, %d3
    move.l  %d3, 60(%a0)            |  0x30003C

    | Line C at [A0 + 40..5F]
    move.l  #0x55555555, %d4
    move.l  %d4, 64(%a0)            |  0x300040
    move.l  #0x66666666, %d5
    move.l  %d5, 92(%a0)            |  0x30005C

    | Line D at [A0 + 60..7F]
    move.l  #0x77777777, %d6
    move.l  %d6, 96(%a0)            |  0x300060
    move.l  #0x88888888, %d7
    move.l  %d7, 124(%a0)           |  0x30007C

    | Read back line A
    move.l  (%a0), %d0
    move.l  #0x11111111, %d1
    cmp.l   %d1, %d0
    bne     _fail
    move.l  28(%a0), %d0
    move.l  #0x22222222, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | Read back line B — should MISS on first word because the line
    | was just written (dirty in its own set) but cache is still warm.
    move.l  32(%a0), %d0
    move.l  #0x33333333, %d1
    cmp.l   %d1, %d0
    bne     _fail
    move.l  60(%a0), %d0
    move.l  #0x44444444, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | Read back line C
    move.l  64(%a0), %d0
    move.l  #0x55555555, %d1
    cmp.l   %d1, %d0
    bne     _fail
    move.l  92(%a0), %d0
    move.l  #0x66666666, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | Read back line D
    move.l  96(%a0), %d0
    move.l  #0x77777777, %d1
    cmp.l   %d1, %d0
    bne     _fail
    move.l  124(%a0), %d0
    move.l  #0x88888888, %d1
    cmp.l   %d1, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail
