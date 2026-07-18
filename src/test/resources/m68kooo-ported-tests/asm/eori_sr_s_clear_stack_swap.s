| eori_sr_s_clear_stack_swap.s — commit-review item #3 regression.
|
| Bug: EORI #imm,SR can legally clear the S bit (XOR flips a live 1 bit
| to 0), exactly like ANDI #imm,SR / MOVE #imm,SR / STOP #imm already
| can — but commit.v's supervisor-A7-bank-swap block (the one that
| stashes the outgoing supervisor A7 into the SSP/MSP PRF slot and
| loads the architectural A7 with the stored USP) only recognised
| SYS_ANDI_SR / SYS_MOVE_SR / SYS_STOP.  EORI was missing.
|
| Observable: right after an EORI-driven S:1->0 transition, the
| architectural A7 register must read the STORED USP value (0 at
| reset, since USP was never explicitly set here) — NOT the outgoing
| supervisor SP.  Empirically confirmed with a throwaway diagnostic
| against both the pre-fix and post-fix RTL:
|   pre-fix  (EORI disjunct disabled): A7 read back 0x00010000 (the
|            stale supervisor SP — never reloaded from USP).
|   post-fix (real behaviour): A7 read back 0x00000000 (USP correctly
|            loaded), matching ANDI's already-correct behaviour.
|
| NOTE on test design: this can NOT be observed via a subsequent
| exception's frame-base address (the more "obvious" architectural
| probe used in an earlier draft of this test) because commit.v has a
| SEPARATE, more general "A7 mirror" mechanism (search
| `a7_mirror_pending`) that resyncs the SSP/USP/ISP PRF slots from
| arch_a7_val on EVERY A7 write while supervisor, independent of S
| transitions — so the SSP slot itself was never actually stale in
| practice; a later exception's frame lands in the right place either
| way.  The one genuinely EORI-specific piece of the swap is the A7
| *reload* from the stored USP at the exact moment S clears
| (`a7_writeback_val <= prf_usp_val`), performed only by the S-clear
| block — and that reload is immediately masked by any later explicit
| write to A7 (e.g. a LEA to set up a user stack), so it must be read
| before anything else touches A7.
|
| Sequence:
|   1. Supervisor: A7 = 0x00010000 (never used again after this).
|   2. EORI.W #0x2000,SR — flips ONLY the S bit (bit 13); pure S:1->0
|      transition, identical architectural effect to ANDI #0xDFFF,SR
|      here.
|   3. Two NOPs to let the retire-time A7 writeback settle.
|   4. Read A7 directly (MOVE.L A7,D0) *before* anything else writes
|      to it.  Must read 0x00000000 (the stored USP).
|
| PASS: 0xC0FFEE00.
| FAIL: 0xDEADBEEF (A7 != stored USP right after the EORI-driven S
|                    clear — the swap's USP-reload half never fired).

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ SSP_BASE,   0x00010000
    .equ EXPECT_A7,  0x00000000   | stored USP (never explicitly set)

_start:
    lea     SSP_BASE, %a7

    | Drop to user mode via EORI — flips ONLY the S bit.
    eori.w  #0x2000, %sr

    | Let the retire-time A7 writeback settle before reading it back.
    nop
    nop

    move.l  %a7, %d0
    cmp.l   #EXPECT_A7, %d0
    bne     _fail

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a1
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a1)
_halt_f:
    bra     _halt_f
