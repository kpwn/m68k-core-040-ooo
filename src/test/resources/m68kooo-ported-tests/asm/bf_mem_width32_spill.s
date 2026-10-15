| bf_mem_width32_spill.s -- static width-32 (raw 0) memory bit-fields with a
| non-zero bit offset: the field spans FIVE bytes, so the spill byte at
| byteAddr+4 must be read (load-only) and written (RMW).  Also the dynamic
| width 0/32 + dynamic/negative/large offset forms and a ([bd,An]) memind
| static form.  Expected values hand-computed from the 68040 PRM definition.
|
| Memory:  0x13000 = 12 34 56 78 9A BC DE F0 0F 1E 2D 3C
|          0x13100 = 00 00 00 00 FF 00 00 00          (spill-only nonzero)
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x00013000, %a0
    move.l  #0x12345678, (%a0)
    move.l  #0x9ABCDEF0, 4(%a0)
    move.l  #0x0F1E2D3C, 8(%a0)
    move.l  #0x00013100, %a1
    clr.l   (%a1)
    move.l  #0xFF000000, 4(%a1)

| ---- static BFEXTU {#o:#32}, o = 1..7 and byte-folded offsets ----
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#1:#32}, %d0
    cmp.l   #0x2468ACF1, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#2:#32}, %d0
    cmp.l   #0x48D159E2, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#3:#32}, %d0
    cmp.l   #0x91A2B3C4, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#4:#32}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#5:#32}, %d0
    cmp.l   #0x468ACF13, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#6:#32}, %d0
    cmp.l   #0x8D159E26, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#7:#32}, %d0
    cmp.l   #0x1A2B3C4D, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#12:#32}, %d0
    cmp.l   #0x456789AB, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#15:#32}, %d0
    cmp.l   #0x2B3C4D5E, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#20:#32}, %d0
    cmp.l   #0x6789ABCD, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#25:#32}, %d0
    cmp.l   #0xF13579BD, %d0
    bne     _fail
    move.l  #0xAAAAAAAA, %d0
    bfextu  (%a0){#31:#32}, %d0
    cmp.l   #0x4D5E6F78, %d0
    bne     _fail
| ---- static BFEXTS {#3:#32}: negative, N must be set ----
    bfexts  (%a0){#3:#32}, %d1
    bpl     _fail
    cmp.l   #0x91A2B3C4, %d1
    bne     _fail
    bfexts  (%a0){#7:#32}, %d1
    cmp.l   #0x1A2B3C4D, %d1
    bne     _fail
| ---- BFTST/BFEXTU/BFFFO where only the spill byte is non-zero ----
    bftst   (%a1){#4:#32}
    beq     _fail
    bfextu  (%a1){#4:#32}, %d2
    cmp.l   #0x0000000F, %d2
    bne     _fail
    bfffo   (%a1){#1:#32}, %d3
    cmp.l   #0x00000020, %d3
    bne     _fail
    bfffo   (%a1){#7:#32}, %d3
    cmp.l   #0x00000020, %d3
    bne     _fail
| ---- static RMW {#4:#32}: the 5th byte must be written ----
    move.l  #0x00013200, %a2
    clr.l   (%a2)
    clr.l   4(%a2)
    bfset   (%a2){#4:#32}
    bne     _fail
    move.l  (%a2), %d4
    cmp.l   #0x0FFFFFFF, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0xF0000000, %d4
    bne     _fail
    moveq   #-1, %d4
    move.l  %d4, (%a2)
    move.l  %d4, 4(%a2)
    bfclr   (%a2){#5:#32}
    bpl     _fail
    move.l  (%a2), %d4
    cmp.l   #0xF8000000, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0x07FFFFFF, %d4
    bne     _fail
    move.l  #0x12345678, (%a2)
    move.l  #0x9ABCDEF0, 4(%a2)
    bfchg   (%a2){#4:#32}
    move.l  (%a2), %d4
    cmp.l   #0x1DCBA987, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0x6ABCDEF0, %d4
    bne     _fail
    clr.l   (%a2)
    clr.l   4(%a2)
    move.l  #0x12345678, %d5
    bfins   %d5, (%a2){#4:#32}
    move.l  (%a2), %d4
    cmp.l   #0x01234567, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0x80000000, %d4
    bne     _fail
    move.l  #0x87654321, %d5
    bfins   %d5, 1(%a2){#3:#32}
    bpl     _fail
    move.l  (%a2), %d4
    cmp.l   #0x0130ECA8, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0x64200000, %d4
    bne     _fail
| ---- dynamic width Dn = 0 / 32 (and 64: low 5 bits only) ----
    moveq   #0, %d7
    bfextu  (%a0){#4:%d7}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #32, %d7
    bfextu  (%a0){#4:%d7}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #64, %d7
    bfextu  (%a0){#4:%d7}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #4, %d6
    moveq   #0, %d7
    bfextu  (%a0){%d6:%d7}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #4, %d6
    moveq   #32, %d7
    bfextu  (%a0){%d6:%d7}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #36, %d6
    moveq   #0, %d7
    bfextu  (%a0){%d6:%d7}, %d0
    cmp.l   #0xABCDEF00, %d0
    bne     _fail
    moveq   #13, %d6
    moveq   #32, %d7
    bfextu  (%a0){%d6:%d7}, %d0
    cmp.l   #0x8ACF1357, %d0
    bne     _fail
    moveq   #4, %d6
    bfextu  (%a0){%d6:#32}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
| ---- negative / large dynamic offsets with width 32 ----
    lea     4(%a0), %a3
    moveq   #-4, %d6
    bfextu  (%a3){%d6:#32}, %d0
    cmp.l   #0x89ABCDEF, %d0
    bne     _fail
    moveq   #-1, %d6
    bfextu  (%a3){%d6:#32}, %d0
    cmp.l   #0x4D5E6F78, %d0
    bne     _fail
    moveq   #-29, %d6
    bfextu  (%a3){%d6:#32}, %d0
    cmp.l   #0x91A2B3C4, %d0
    bne     _fail
    moveq   #-32, %d6
    bfextu  (%a3){%d6:#32}, %d0
    cmp.l   #0x12345678, %d0
    bne     _fail
    move.l  #0x00013000-0x200, %a3
    move.l  #0x1004, %d6
    bfextu  (%a3){%d6:#32}, %d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    moveq   #1, %d6
    moveq   #0, %d7
    bfffo   (%a1){%d6:%d7}, %d3
    cmp.l   #0x00000020, %d3
    bne     _fail
    bftst   (%a1){%d6:%d7}
    beq     _fail
    clr.l   (%a2)
    clr.l   4(%a2)
    moveq   #4, %d6
    bfset   (%a2){%d6:%d7}
    move.l  (%a2), %d4
    cmp.l   #0x0FFFFFFF, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0xF0000000, %d4
    bne     _fail
| ---- memory-indirect ([16,A1]) static {#4:#32} ----
    move.l  #0x00013300, %a1
    move.l  #0x00013000, 16(%a1)
    move.l  #0xAAAAAAAA, %d0
    .word   0xE9F1, 0x0100, 0x0164, 0x0010   | bfextu ([16,%a1]){#4:#32},%d0
    cmp.l   #0x23456789, %d0
    bne     _fail
    move.l  #0x00013200, 16(%a1)
    clr.l   (%a2)
    clr.l   4(%a2)
    .word   0xEEF1, 0x0100, 0x0164, 0x0010   | bfset ([16,%a1]){#4:#32}
    move.l  (%a2), %d4
    cmp.l   #0x0FFFFFFF, %d4
    bne     _fail
    move.l  4(%a2), %d4
    cmp.l   #0xF0000000, %d4
    bne     _fail

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
