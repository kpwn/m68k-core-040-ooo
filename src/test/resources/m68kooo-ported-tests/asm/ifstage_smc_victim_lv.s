| ifstage_smc_victim_lv.s — SMC staleness in if_stage's VICTIM buffer
|
| Task #166 / memory_model_review.md BUG-11 — this is the exact scenario
| the review called out by name:
|
|   "the canonical 68040 SMC sequence — move.l d0,(a0) / cpushl / jmp (a0)
|    — has a real hole: the I-cache line is correctly dropped, but if that
|    line is sitting in if_stage's lv, the jmp soft-hits it and decode
|    consumes pre-patch bytes with zero refetch."
|
| The victim slot is the worst of the three because it deliberately
| SURVIVES a hard redirect (if_stage.v:61-62; the br_redirect branch at
| :455-468 clears l0_valid/l1_valid and never touches lv_valid), so a
| stale victim line can outlive an arbitrary number of branches.
|
| Residency construction: line LA1 is executed straight through into line
| LB1.  The deferred rotate (if_stage.v:546-557) demotes the old l0 into
| lv on the line cross, so lv == LA1 for as long as PC stays inside LB1.
| The patch + cpushl + backward branch all fit inside LB1's 16 bytes, so
| no second rotate can evict it.
|
| Residency is also confirmed independently by IFSTAGE_DEBUG=1, which
| prints e.g.
|   [ifstage] SOFT_HIT rdir=40800050 hit_l0=0 hit_l1=0 hit_lv=1 ...
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0011 — STALE: pre-patch moveq #$11 executed => BUG-11 real
|   0xDEAD00A0 — the store never reached memory (test-setup problem)

    .text
_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3
    move.l  #_priv,    0x00000020       | vec 8

    lea     LA1, %a0                    | patch site (line-aligned)
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0, %d2                     | pass counter
    moveq   #0x55, %d7                  | poison
    bra     LA1

| ── Line N.  Executed straight through on pass 1, so it lands in lv. ──
    .balign 16
LA1:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    tst.l   %d2                         | +2
    bne.s   LC1                         | +4  (2nd pass exits here)
    moveq   #1, %d2                     | +6
    nop                                 | +8
    nop                                 | +10
    nop                                 | +12
    nop                                 | +14  falls through -> lv := LA1

| ── Line N+1.  Whole patch sequence fits here, so lv stays == LA1. ────
    .balign 16
LB1:
    move.w  %d1, (%a0)                  | +0  patch LA1 (now in lv)
    cpushl  %bc, (%a0)                  | +2
    bra.s   LA1                          | +4  taken -> lv soft hit
    nop
    nop
    nop
    nop
    nop

    .balign 16
LC1:
    | Memory first: rules out a lost store before we blame the buffers.
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
