| ifstage_smc_l0_backbranch.s — SMC staleness in if_stage's l0 buffer
|
| Task #166 / memory_model_review.md BUG-11.
|
| `if_stage.v` holds three 16-byte line buffers (l0, l1, lv) IN FRONT OF
| the I-cache and has no invalidate input of any kind (if_stage.v:124-174).
| The D-cache SMC snoop and the CPUSH/CINV maintenance bundle both
| terminate at icache.v.  A taken branch whose target line is still
| sitting in one of those buffers takes the "soft hit" path
| (if_stage.v:401-449) and decode consumes the buffered bytes with zero
| refetch.
|
| This test targets the l0 buffer.  The entire loop lives inside ONE
| 16-byte line, so l0 is provably that line for the whole test:
| `pd_valid_w` (if_stage.v:294-296) requires `l0_valid && l0_addr ==
| pc_line` for EVERY instruction decoded, so if any of these instructions
| executed at all, l0 held this line.  That is the residency proof — it
| needs no waveform.
|
| Sequence (the canonical 68040 SMC idiom):
|   1st pass  : moveq #$11,d7 / set d2 / patch word at offset 0 / cpushl
|               / bra back to offset 0
|   2nd pass  : the patched moveq #$22,d7 must execute.
|
| A real 68040 flushes its instruction FIFO on a taken branch and
| refetches from the (just-invalidated) I-cache, so #$22 is architecturally
| required here.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0011 — STALE: the pre-patch moveq #$11 executed => BUG-11 real
|   0xDEAD00A0 — the store never reached memory (test-setup problem)
|   0xDEAD0004/2/3/8 — illegal / bus error / address error / privilege

    .text
_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3
    move.l  #_priv,    0x00000020       | vec 8

    lea     LT3, %a0                    | patch site (line-aligned)
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0, %d2                     | pass counter
    moveq   #0x55, %d7                  | poison

    | Prove the code region is store-visible at all before relying on it.
    lea     probe_word, %a1
    move.w  #0x1234, (%a1)
    cpushl  %bc, (%a1)
    move.w  (%a1), %d3
    cmp.w   #0x1234, %d3
    bne     _fail_nowrite

    bra     LA3

| ── The whole loop is exactly one 16-byte line ────────────────────────
    .balign 16
LA3:
LT3:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    tst.l   %d2                         | +2
    bne.s   LC3                         | +4  (2nd pass exits here)
    moveq   #1, %d2                     | +6
    move.w  %d1, (%a0)                  | +8  patch offset 0 of THIS line
    cpushl  %bc, (%a0)                  | +10
    bra.s   LT3                         | +12 taken branch -> l0 soft hit
    nop                                 | +14

    .balign 16
LC3:
    | Check MEMORY first: this rules out "the store never landed" as an
    | explanation before we blame the fetch buffers.  The cpushl pushed +
    | invalidated the line, so this load refills from real memory.
    move.w  (%a0), %d4
    cmp.w   #0x7E22, %d4
    bne     _fail_nopatch
    | Memory holds the NEW instruction.  Did the CPU execute it?
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
_fail_nowrite:
    move.l  #0xDEAD00A1, 0xFFFF0000
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

    .balign 16
probe_word:
    .word   0
    .word   0
