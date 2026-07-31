| ifstage_smc_far_control.s — CONTROL for the BUG-11 SMC test family
|
| Task #166 / memory_model_review.md BUG-11.
|
| Structurally identical to ifstage_smc_l0_backbranch.s /
| ifstage_smc_l1_forward.s / ifstage_smc_victim_lv.s — same patch word,
| same cpushl, same taken branch to the patched instruction — with ONE
| difference: the branch target sits 512 bytes away, far outside the
| 32-byte reach of if_stage's l0/l1/lv buffers, and has never been
| fetched.  The redirect therefore MISSES all three buffers, falls to the
| hard-redirect path, and refetches through the I-cache.
|
| This is the positive control that makes the negative half of the family
| trustworthy.  It proves the whole store -> D-cache -> snoop/CPUSH ->
| I-cache -> refetch chain works end to end, so when the l0/l1/lv variants
| execute pre-patch bytes it is specifically the if_stage line buffers at
| fault, not a lost store, an unflushed D-cache line, or a broken snoop.
|
| Expected: PASS.  If this ever FAILS, the other three tests in the family
| prove nothing and the real bug is somewhere in the D->I coherency path.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0011 — stale bytes executed even from a never-buffered line
|   0xDEAD00A0 — the store never reached memory

    .text
_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4
    move.l  #_buserr,  0x00000008       | vec 2
    move.l  #_addrerr, 0x0000000c       | vec 3
    move.l  #_priv,    0x00000020       | vec 8

    lea     LFAR, %a0                   | patch site, 512B ahead
    move.w  #0x7E22, %d1                | replacement: moveq #$22,%d7
    moveq   #0x55, %d7                  | poison

    move.w  %d1, (%a0)                  | patch
    cpushl  %bc, (%a0)                  | push D-cache line + invalidate I
    jmp     (%a0)                       | taken -> buffer MISS -> refetch

    | (unreachable)
    move.l  #0xDEAD00FF, 0xFFFF0000
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

| ── 512 bytes of never-executed filler, so LFAR's line cannot possibly
|    have been prefetched (if_stage runs at most one line ahead). ──────
    .balign 16
_filler:
    .space  512, 0x71

    .balign 16
LFAR:
    moveq   #0x11, %d7                  | +0  <- PATCH SITE
    bra     LCHK
    nop
    nop
    nop
    nop
    nop

    .balign 16
LCHK:
    move.w  (%a0), %d4
    cmp.w   #0x7E22, %d4
    bne     _fail_nopatch
    cmp.l   #0x22, %d7
    bne     _fail_stale
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .
