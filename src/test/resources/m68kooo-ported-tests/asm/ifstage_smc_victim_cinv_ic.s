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
| ⚠️ POSTURE: THIS TEST IS ONLY VALID WITH CACHES OFF. Do NOT add it to the
| cache-mode sweep manifest, and do not read a copyback failure here as a core
| bug (2026-09-10: I did exactly that and had to retract it).
|
| Its premise -- quoted above -- is that "the I-cache refill snoops" the word
| still dirty in the D-cache. THIS CORE DELIBERATELY HAS NO SUCH INTERNAL
| D-to-I SNOOP: see commits 69c7b59 / c994092 and the investigation note in
| smc_dcache_to_icache.s.
|
| BE PRECISE about the 68040 claim (the loose version "the 68040 has no snoop"
| is WRONG and will mislead the next reader):
|   * the MC68040 DOES have external bus snooping, for ALTERNATE bus masters
|     (DMA, another CPU), via the snoop-control bits;
|   * what it does NOT have is coherency between its OWN I and D caches for its
|     OWN stores. A store that hits in the D-cache never reaches the bus, so the
|     instruction side has nothing to observe.
|
| The load-bearing evidence is the shape of the MANDATORY software idiom, not a
| manual quotation: the 68040 SMC sequence is CPUSHL -- a PUSH. If instruction
| fetches consulted the D-cache, pushing would be pointless and invalidating the
| I-line alone would suffice. Linux/m68k flush_icache_range and Mac OS
| FlushCodeCacheRange both push dirty data out first.
|
| WARNING: our oracle CANNOT arbitrate this. Musashi models no caches at all
| (its CACR is commented "unemulated" and it only disassembles CPUSH), so no
| lock-step run can confirm or refute cache-coherency semantics. The M68040UM
| cache chapter is the authority, not this test suite.
|
| So the expectation here is posture-dependent, and only one posture is legal:
|   caches OFF (CACR=0): the store reaches MEMORY, the I-refill sees the new
|       word, d7 == 0x22 -> PASS. This is the posture the corpus runs.
|   caches ON  (copyback): the word stays DIRTY in the D-cache, the I-refill
|       correctly reads STALE memory, d7 == 0x11 -> reports 0xDEAD0011. That is
|       CORRECT 68040 behaviour, not BUG-11.
|
| The variants that ARE valid under copyback are the ones using the mandatory
| idiom -- ifstage_smc_victim_cpusha.s and ifstage_smc_victim_dc_ic.s -- and both
| PASS there, which is the real evidence that BUG-11 is absent from this core.
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
