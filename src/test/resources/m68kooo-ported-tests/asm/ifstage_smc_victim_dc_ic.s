| ifstage_smc_victim_dc_ic.s — BUG-11 scope variant: CPUSHL %dc + CINVL %ic
|
| Task #166 / memory_model_review.md BUG-11.  Identical to
| ifstage_smc_victim_lv.s except the maintenance is split into the two
| scope-specific instructions a careful 68040 programmer would use:
|
|     cpushl  %dc, (%a0)      | writeback the patched data line
|     cinvl   %ic, (%a0)      | drop the stale instruction line
|
| This closes the CINV-vs-CPUSH-vs-both axis alongside
| ifstage_smc_victim_lv.s (cpushl %bc) and ifstage_smc_victim_cinv_ic.s
| (cinvl %ic alone).  All three are expected to behave identically,
| because if_stage.v's line buffers have no invalidate input at all —
| no combination of cache-maintenance instructions can reach them.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0011 — STALE: pre-patch moveq #$11 executed => BUG-11 real
|   0xDEAD00A0 — the store never became visible

    .text
_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3
    move.l  #_priv,    0x00000020       | vec 8

    lea     LA1, %a0
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0, %d2
    moveq   #0x55, %d7
    bra     LA1

    .balign 16
LA1:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    tst.l   %d2                         | +2
    bne.s   LC1                         | +4
    moveq   #1, %d2                     | +6
    nop                                 | +8
    nop                                 | +10
    nop                                 | +12
    nop                                 | +14  falls through -> lv := LA1

    .balign 16
LB1:
    move.w  %d1, (%a0)                  | +0
    cpushl  %dc, (%a0)                  | +2  data scope: writeback
    cinvl   %ic, (%a0)                  | +4  instruction scope: invalidate
    bra.s   LA1                         | +6  taken -> lv soft hit
    nop
    nop
    nop
    nop

    .balign 16
LC1:
    move.w  (%a0), %d4
    cmp.w   #0x7E22, %d4
    bne     _fail_nopatch
    cmp.l   #0x22, %d7
    bne     _fail_stale
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_stale:
    move.l  #0xDEAD0011, 0xFFFF0000
    bra     .
_fail_nopatch:
    move.l  #0xDEAD00A0, 0xFFFF0000
    bra     .
_illegal:
    move.l  #0xDEAD0004, 0xFFFF0000
    bra     .
_buserr:
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .
_addrerr:
    move.l  #0xDEAD0003, 0xFFFF0000
    bra     .
_priv:
    move.l  #0xDEAD0008, 0xFFFF0000
    bra     .
