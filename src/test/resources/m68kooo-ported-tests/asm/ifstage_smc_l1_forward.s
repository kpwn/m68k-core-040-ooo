| ifstage_smc_l1_forward.s — SMC staleness in if_stage's l1 buffer
|
| Task #166 / memory_model_review.md BUG-11.  Companion to
| ifstage_smc_l0_backbranch.s (l0) and ifstage_smc_victim_lv.s (lv).
|
| Targets the l1 (look-ahead) buffer.  Residency is structurally
| guaranteed, not assumed:
|
|   assign pd_valid_w = ... l0_valid && (l0_addr == pc_line) &&
|                       (l0_fault || (pc_off == 4'd0) || l1_valid);
|                                                   -- if_stage.v:294-296
|
| Decode CANNOT advance past offset 0 of a line unless l1 is valid, and
| the only address the kick gate ever loads into l1 is l0_addr+1
| (if_stage.v:578-580).  So by the time the 3rd instruction of line LA2
| retires, l1 provably holds line LB2 — with PRE-PATCH bytes, because the
| l1 fill was kicked before the patching store even issued.
|
| We then patch a word in LB2, cpushl it, and take a branch into it.  A
| taken branch flushes a real 68040's instruction FIFO and refetches from
| the (now invalidated) I-cache, so the patched instruction is required.
| if_stage instead takes the hit_l1 soft-hit path (if_stage.v:409-421)
| and promotes the stale l1 straight into l0.
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

    lea     LB2, %a0                    | patch site: first word of next line
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0x55, %d7                  | poison
    bra     LA2

| ── LA2 = line N.  l1 is forced to hold line N+1 (= LB2) before the
|    patching store even executes, because decode stalls at offset 2
|    until l1_valid. ─────────────────────────────────────────────────
    .balign 16
LA2:
    nop                                 | +0   forces the l1 kick
    move.w  %d1, (%a0)                  | +2   patch LB2 (line N+1, in l1)
    cpushl  %bc, (%a0)                  | +4
    bra.s   LB2                         | +6   taken -> l1 soft hit
    nop                                 | +8
    nop                                 | +10
    nop                                 | +12
    nop                                 | +14

    .balign 16
LB2:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    bra     LC2
    nop
    nop
    nop
    nop
    nop

    .balign 16
LC2:
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
