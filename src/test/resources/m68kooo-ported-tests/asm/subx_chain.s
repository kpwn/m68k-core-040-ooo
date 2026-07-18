| subx_chain.s — SUBX.L chain using X flag between two 32-bit halves
|
| Hypothesis: SUB.L sets X=C=<borrow>.  SUBX.L then consumes X as the
| borrow-in for the high half of a 64-bit subtract.
|
| Compute (0x00000002_00000000) − (0x00000000_00000001) = 0x00000001_FFFFFFFF
|   Low:  0x00000000 − 0x00000001 = 0xFFFFFFFF   (borrow → X=C=1)
|   High (SUBX): 0x00000002 − 0x00000000 − X(1) = 0x00000001
|
| Layout:
|   D0 = a_lo = 0x00000000   D1 = a_hi = 0x00000002
|   D2 = b_lo = 0x00000001   D3 = b_hi = 0x00000000
|   SUB.L  D2, D0   → D0 = 0xFFFFFFFF, X=1
|   SUBX.L D3, D1   → D1 = 0x00000001
|
| Follow-up with a no-borrow low subtract to verify X=0 path:
|   (0x00000001_00000005) − (0x00000000_00000003) = 0x00000001_00000002
|   Low:  0x00000005 − 0x00000003 = 0x00000002, X=0
|   High (SUBX): 0x00000001 − 0x00000000 − 0 = 0x00000001

    .text
    .org 0

_start:
    | ── First chain: low borrow propagates to high ──
    move.l  #0x00000000, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000001, %d2
    move.l  #0x00000000, %d3
    sub.l   %d2, %d0                 | D0 = 0xFFFFFFFF, X=1
    subx.l  %d3, %d1                 | D1 = 2 - 0 - 1 = 1

    move.l  #0xFFFFFFFF, %d7
    cmp.l   %d7, %d0
    bne     _fail
    move.l  #0x00000001, %d7
    cmp.l   %d7, %d1
    bne     _fail

    | ── Second chain: no-borrow case ──
    move.l  #0x00000005, %d0
    move.l  #0x00000001, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000000, %d3
    sub.l   %d2, %d0                 | D0 = 2, X=0
    subx.l  %d3, %d1                 | D1 = 1 - 0 - 0 = 1

    move.l  #0x00000002, %d7
    cmp.l   %d7, %d0
    bne     _fail
    move.l  #0x00000001, %d7
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
