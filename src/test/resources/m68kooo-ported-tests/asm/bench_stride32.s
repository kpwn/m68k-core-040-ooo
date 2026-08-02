| bench_stride32.s — zero-reuse 32 B stride walk (D-cache BYPASS variant)
|
| The deliberate ADVERSARIAL counterpart to bench_wset_1k.  One 4-byte
| load per 32 B D-cache line, walking 16 KiB linearly: no temporal reuse
| (each line is touched once) and no spatial reuse (only one word of each
| 32 B line is ever read).  This is the pattern where a write-back cache
| with no critical-word-first and no early restart LOSES to a bypass — it
| fetches 32 B and hands back 4.
|
| Keeping this pair honest matters: bench_wset_1k alone would make the
| cache look unconditionally good.  Publish both or publish neither.
|
| This is the BYPASS half of the pair; see bench_stride32_dcache.
|
| Array: 0x00200000 .. 0x00203FFF (16 KiB = 512 lines)
| Work : 512 loads, one per line, accumulated into D0.

    .text
    .org 0

_start:
    move.l  #0, %d0             | accumulator
    move.l  #512, %d1           | line counter
    lea     0x00200000, %a0

_walk:
    move.l  (%a0), %d4
    add.l   %d4, %d0
    add.l   #32, %a0            | next 32 B line
    sub.l   #1, %d1
    bne     _walk

    lea     0x00204000, %a1
    move.l  %d0, (%a1)

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)

_halt:
    stop    #0x2700
    bra     _halt
