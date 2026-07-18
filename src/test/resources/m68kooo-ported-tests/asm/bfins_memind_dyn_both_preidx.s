| bfins_memind_dyn_both_preidx.s — Task #240 (G3)
|
| BFINS Dn, ([bd,An,Xn.L*sc],od){Do:Dw} — pre-indexed memind dst, both
| offset and width dynamic.  Exercises the V2 EA-recompute crack that
| holds merged data in TMP1 and rebuilds the EA in TMP2 alone via scale-
| doublings.
|
| Phase plan (s = scale_log2):
|   ph0       T1 = An + bd
|   ph1       T2 = Xn (MOV/EXT)
|   ph2..1+s  T2 *= 2 (scale doublings)
|   ph 2+s    T1 += T2          (T1 = An+bd+Xn*sc)
|   ph 3+s    LOAD.L (T1) -> T1  (memory-indirect)
|   ph 4+s    T1 += od           (final EA, NOT preserved)
|   ph 5+s    T2 = PACK(off,wid)
|   ph 6+s    LOAD.L (T1) -> T1  (T1 = field data)
|   ph 7+s    BFINS T1,Dn_insert,T2 -> T1
|   ph 8+s    T2 = Xn (recompute starts)
|   ph 9+s..(8+s)+s  T2 *= 2 (s doublings)
|   ph 9+2s   T2 += An
|   ph 10+2s  T2 += bd
|   ph 11+2s  LOAD.L (T2) -> T2 (memory-indirect)
|   ph 12+2s  T2 += od (final EA)
|   ph 13+2s  STORE.L @T2 <- T1
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    | --- Test 1: scale=2, bd=+0x10, od=+0 ---
    | inner EA = A4 + bd + D1*2 = 0x40800B00 + 0x10 + 0x10*2 = 0x40800B30
    | Memory at [0x40800B30] = pointer 0x40800C00 (field area)
    | Final EA = pointer + od = 0x40800C00
    | Initial field at [0x40800C00] = 0x12345678
    | D0 = insert (low 16 bits = 0xABCD); off=8 (D2); wid=16 (D3)
    | Mask = 0xFFFF << (32-8-16) = 0xFFFF00
    | Result = (0x12345678 & ~0xFFFF00) | (0xABCD << 8) = 0x12ABCD78
    lea     0x40800B00, %a4
    move.l  #0x40800C00, 0x30(%a4)        | inner ptr at A4+0x30
    lea     0x40800C00, %a5
    move.l  #0x12345678, (%a5)            | initial field
    move.l  #0x10,       %d1              | index
    move.l  #0x0000ABCD, %d0              | insert
    moveq   #8,          %d2              | offset
    moveq   #16,         %d3              | width
    | BFINS D0, ([+0x10,A4,D1.L*2],+0){D2:D3}
    bfins   %d0, ([0x10,%a4,%d1.l*2],0){%d2:%d3}
    move.l  (%a5), %d4
    cmp.l   #0x12ABCD78, %d4
    bne     _fail

    | --- Test 2: scale=4, bd=-2, od=+4, BS=0 ---
    | inner EA = A4 + bd + D1*4 = 0x40800B00 + (-2) + 0x10*4 = 0x40800B3E
    | But 0x40800B3E is misaligned for LONG; pick D1=4 to avoid that.
    | Use D1=4, scale=4: D1*4 = 0x10. inner EA = 0x40800B00 - 2 + 0x10 = 0x40800B0E
    | Hmm misaligned. Use bd=+0x20, D1=8, scale=2: 0x40800B00+0x20+0x10 = 0x40800B30 reused.
    | Easier: use bd=-0x20, D1=8, scale=4: 0x40800B00-0x20+0x20 = 0x40800B00 (collision).
    | Cleanest: bd=+0x40, D1=4, scale=4 -> 0x40800B00+0x40+0x10 = 0x40800B50.
    move.l  #0x40800D00, 0x50(%a4)        | inner ptr at A4+0x50
    lea     0x40800D04, %a5               | with od=+4
    move.l  #0xFFFFFFFF, (%a5)            | initial field
    move.l  #4,          %d1              | index (D1*4 = 0x10)
    move.l  #0x00000055, %d0              | insert (low 8 bits)
    moveq   #12,         %d2              | offset
    moveq   #8,          %d3              | width
    | Mask = 0xFF << (32-12-8) = 0xFF << 12 = 0xFF000.
    | Result = (0xFFFFFFFF & ~0xFF000) | (0x55 << 12) = 0xFFF55FFF.
    bfins   %d0, ([0x40,%a4,%d1.l*4],4){%d2:%d3}
    move.l  (%a5), %d4
    cmp.l   #0xFFF55FFF, %d4
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
