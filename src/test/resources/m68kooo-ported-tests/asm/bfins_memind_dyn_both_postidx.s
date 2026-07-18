| bfins_memind_dyn_both_postidx.s — Task #240 (G3)
|
| BFINS Dn, ([bd,An],Xn.L*sc,od){Do:Dw} — post-indexed memind dst, both
| offset and width dynamic.  Exercises the V2 EA-recompute crack that
| holds merged data in TMP1 and rebuilds the EA in TMP2 alone via linear
| Xn adds (post-idx semantics force the inner load before the Xn*sc add,
| leaving no room for doublings in one scratch).
|
| Phase plan (s = scale_log2, sc = 1<<s):
|   ph0       T1 = An + bd
|   ph1       LOAD.L (T1) -> T1   (T1 = stored ptr)
|   ph2       T2 = Xn (MOV/EXT)
|   ph3..2+s  T2 *= 2 (scale doublings)
|   ph 3+s    T1 += T2            (T1 = ptr + Xn*sc)
|   ph 4+s    T1 += od            (final EA, NOT preserved)
|   ph 5+s    T2 = PACK(off,wid)
|   ph 6+s    LOAD.L (T1) -> T1   (T1 = field data)
|   ph 7+s    BFINS T1,Dn_insert,T2 -> T1
|   ph 8+s    T2 = An + bd        (recompute, TMP2-only)
|   ph 9+s    LOAD.L (T2) -> T2   (T2 = stored ptr)
|   ph 10+s..(9+s)+sc  T2 += Xn   (sc linear adds)
|   ph 10+s+sc T2 += od
|   ph 11+s+sc STORE.L @T2 <- T1
|
| Index .L only (linear-add path can't sign-extend Xn.W with TMP2 holding
| the loaded ptr).  PRM-conformant tests that exercise .W go through the
| legacy path; that's a follow-up.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0
_start:
    | --- Test 1: scale=1, bd=+0x10, od=+0x20 ---
    | inner ptr at [A4 + 0x10] = 0x40800B10  (note: post-idx loads from
    | (An + bd) WITHOUT adding Xn first)
    | EA = *(A4+0x10) + D1*1 + 0x20 = 0x40800C00 + 0x40 + 0x20 = 0x40800C60
    | Initial field at [0x40800C60] = 0x12345678
    | D0 = 0xABCD insert; D2=8 off; D3=16 wid
    | Mask = 0xFFFF << 8 = 0xFFFF00; Result = (0x12345678 & ~0xFFFF00) | 0xABCD00
    |        = 0x12000078 | 0xABCD00 = 0x12ABCD78
    lea     0x40800B00, %a4
    move.l  #0x40800C00, 0x10(%a4)        | inner ptr at A4+bd
    lea     0x40800C60, %a5
    move.l  #0x12345678, (%a5)            | initial field
    move.l  #0x40,       %d1              | index (linear add)
    move.l  #0x0000ABCD, %d0              | insert
    moveq   #8,          %d2              | offset
    moveq   #16,         %d3              | width
    | BFINS D0, ([+0x10,A4],D1.L*1,+0x20){D2:D3}
    bfins   %d0, ([0x10,%a4],%d1.l*1,0x20){%d2:%d3}
    move.l  (%a5), %d4
    cmp.l   #0x12ABCD78, %d4
    bne     _fail

    | --- Test 2: scale=2, bd=+0x40, od=+8 ---
    | inner ptr at [A4 + 0x40] = 0x40800B40
    | EA = *(A4+0x40) + D1*2 + 8 = 0x40800D00 + 0x10 + 8 = 0x40800D18
    | Use 0x40800D18 as field; initial = 0xFFFFFFFF
    | D0=0x55 insert (low 8 bits); D2=12 off; D3=8 wid
    | Mask = 0xFF << (32-12-8) = 0xFF << 12 = 0xFF000
    | Result = (0xFFFFFFFF & ~0xFF000) | (0x55 << 12) = 0xFFF55FFF
    move.l  #0x40800D00, 0x40(%a4)
    lea     0x40800D18, %a5
    move.l  #0xFFFFFFFF, (%a5)
    move.l  #8,          %d1              | D1*2 = 0x10
    move.l  #0x00000055, %d0
    moveq   #12,         %d2
    moveq   #8,          %d3
    bfins   %d0, ([0x40,%a4],%d1.l*2,8){%d2:%d3}
    move.l  (%a5), %d4
    cmp.l   #0xFFF55FFF, %d4
    bne     _fail

    | --- Test 3: scale=4, bd=+0x60, od=-4 ---
    | inner ptr at [A4 + 0x60] = 0x40800B60
    | EA = *(A4+0x60) + D1*4 + (-4) = 0x40800E00 + 0x10 + (-4) = 0x40800E0C
    | initial = 0xAABBCCDD; D0=0xFF; D2=24; D3=8
    | Mask = 0xFF << (32-24-8) = 0xFF << 0 = 0xFF
    | Result = (0xAABBCCDD & ~0xFF) | 0xFF = 0xAABBCCFF
    move.l  #0x40800E00, 0x60(%a4)
    lea     0x40800E0C, %a5
    move.l  #0xAABBCCDD, (%a5)
    move.l  #4,          %d1              | D1*4 = 0x10
    move.l  #0x000000FF, %d0
    moveq   #24,         %d2
    moveq   #8,          %d3
    bfins   %d0, ([0x60,%a4],%d1.l*4,-4){%d2:%d3}
    move.l  (%a5), %d4
    cmp.l   #0xAABBCCFF, %d4
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
