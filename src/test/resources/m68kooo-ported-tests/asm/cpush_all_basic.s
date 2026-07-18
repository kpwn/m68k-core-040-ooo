| cpush_all_basic.s — CPUSH DC, ALL flushes every dirty line.
|
| Strategy:
|   1. Dirty four distinct cache lines by storing V_k at addr X_k.
|      Four addresses are chosen 64 bytes apart so each lands in a
|      different cache set (line = 32 bytes → addresses differ in
|      bits[9:5] which is the set index).
|   2. CPUSH DC, ALL — flush every dirty line to memory.
|   3. CINV DC, ALL — drop everything (memory now has V_1..V_4).
|   4. Reload each address.  All four must read their original V_k
|      value (from memory, after CPUSH).  If any reads 0xFFFFFFFF,
|      CPUSH ALL missed that line.  If any reads wrong bytes, CINV
|      failed to invalidate (stale from cache).
|
| PASS: all four reloads match.
| FAIL: any mismatch.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Four line-aligned addresses in four different sets.
    | Set spacing: line = 32 B, so bits[9:5] pick the set.  We use 0x80
    | apart (= 4 lines apart = 4 different sets) to be clearly distinct.
    move.l  #0x00010000, %a0
    move.l  #0x00010080, %a1
    move.l  #0x00010100, %a2
    move.l  #0x00010180, %a3

    | V1..V4 — unique constants so we can tell them apart.
    move.l  #0x11111111, (%a0)
    move.l  #0x22222222, (%a1)
    move.l  #0x33333333, (%a2)
    move.l  #0x44444444, (%a3)

    | CPUSH DC, ALL — opword 0xF478.
    cpusha  %dc

    | CINV DC, ALL — drop every line.  Opword 0xF458.
    cinva   %dc

    | Reload each address.  Cache is empty after CINV; each load refills
    | from memory.  If CPUSH wrote them all back, the reloads match V_k.
    move.l  (%a0), %d0
    move.l  (%a1), %d1
    move.l  (%a2), %d2
    move.l  (%a3), %d3

    | Check d0 = 0x11111111
    move.l  #0x11111111, %d4
    cmp.l   %d4, %d0
    bne     _fail
    move.l  #0x22222222, %d4
    cmp.l   %d4, %d1
    bne     _fail
    move.l  #0x33333333, %d4
    cmp.l   %d4, %d2
    bne     _fail
    move.l  #0x44444444, %d4
    cmp.l   %d4, %d3
    bne     _fail

    | All four matched → PASS.
    lea     0xFFFF0000, %a4
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a4)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a4
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a4)
_halt_fail:
    bra     _halt_fail
