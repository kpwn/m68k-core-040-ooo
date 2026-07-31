| ifstage_smc_victim_cinv_ic.s — BUG-11 scope variant: CINV %ic alone
|
| Task #166 / memory_model_review.md BUG-11.  Identical to
| ifstage_smc_victim_lv.s except the maintenance instruction is
| `cinvl %ic,(%a0)` — instruction-cache scope only, no D-cache push.
|
| Why this variant matters: it separates "the buffers ignore the
| maintenance op" from "the maintenance op happened to also fix things
| via the D-cache writeback".  With CINV %ic the patched word stays dirty
| in the D-cache; the I-cache refill snoops it (see
| smc_dcache_to_icache.s), so a correct front end still executes the NEW
| instruction.  if_stage's lv buffer is downstream of the I-cache and is
| invalidated by nothing at all, so the result is expected to be
| identical to the CPUSH %bc variant — which is the point: no scope, no
| cache, no maintenance instruction reaches these buffers.
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
    cinvl   %ic, (%a0)                  | +2  I-cache scope only
    bra.s   LA1                         | +4  taken -> lv soft hit
    nop
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
