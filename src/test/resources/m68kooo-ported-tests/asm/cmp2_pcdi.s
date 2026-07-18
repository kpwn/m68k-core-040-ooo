| cmp2_pcdi.s — CMP2.L (d16,PC): bounds live in a code-adjacent data
| island.  Before the 2026-07-15 fix the V2 crack read the bounds from
| REG_TMP0 + sx32(d16) — a wild load that bus-errored — instead of
| pd_pc + 4 + d16 (the EA d16 ext word sits past opword + Rn/B ext).
|
| Cases: in-range (C=0,Z=0), above-range (C=1), on-bound (Z=1,C=0).
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    bra     1f

    .balign 4
2:  .long   100                        | lower bound
    .long   200                        | upper bound

1:  move.l  #150, %d1
    cmp2.l  (2b,%pc), %d1              | in range
    bcs     _fail                      | C must be 0
    beq     _fail                      | Z must be 0

    move.l  #201, %d1
    cmp2.l  (2b,%pc), %d1              | above range
    bcc     _fail                      | C must be 1

    move.l  #200, %d1
    cmp2.l  (2b,%pc), %d1              | on the upper bound
    bne     _fail                      | Z must be 1
    bcs     _fail                      | C must be 0

    | PASS
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
