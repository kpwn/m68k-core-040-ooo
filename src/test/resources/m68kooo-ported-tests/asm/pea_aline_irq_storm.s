| pea_aline_irq_storm.s -- High-fidelity repro attempt for the HW-only
| PEA store-loss wedge documented in docs/ila_v5_lost_store_bisect.md
| and docs/lsu_committed_store_flush_gap.md.  Directed sim reproduction
| has never fired for this bug; the new ingredient under test here is
| an async autovector IRQ landing inside the PEA->A-line window (see
| this file's .args + the _sweep_p*/_lvl2 siblings).
|
| Structure mirrors ROM @ 0x4086aba0:
|   - 3 back-to-back PC-relative PEAs onto a cold copyback stack line,
|     landing the 3rd (critical) store at cache-line-offset 0
|     (STACK_BASE, matching HW's 0x001FE080).
|   - Immediately (zero instruction gap) a 5-iteration loop, each
|     iteration firing TWO A-line opwords (0xA53D / 0xA029 -- the
|     literal ROM bit patterns; low 12 bits are irrelevant, any
|     opcode with top nibble 0xA traps to vector 0x0a) back-to-back.
|   - The A-line handler performs exactly 3 internal nested A-line
|     re-traps per top-level trap (mirrors the multiple vec=0x0a hits
|     at handler PCs the HW exc-ring capture showed between opword
|     traps).
|   - .args sweeps drive +ipl=<cycle>:1 (and a level-2 bonus variant)
|     to land an autovector IRQ inside the PEA->A-line window or
|     inside the nested-handler storm itself -- exercising the
|     LSU S_ST_BUF flush-drop candidate at rtl/core/mem/lsu.v:655
|     (flush_en && cur_outside_keep winning over commit_store_en in
|     the same cycle).
|
| Pass: all 3 popped return values match their PEA'd addresses and
| %a7 nets back to STACK_HI.  FAIL sentinels are per-site and
| distinguish "popped the 0x6DB6DB6D stripe fill" (the literal HW
| corruption signature) from "popped something else corrupt", so a
| RED run immediately tells you which of the 3 PEA stores was lost
| and whether the read-back value was literally the stale stripe.

    .text
    .org 0

    .equ NEST_MAX,     4              | 1 top-level + 3 nested re-traps
    .equ DEPTH_ADDR,   0x000F0010
    .equ STRIPE,       0x6DB6DB6D
    .equ STACK_BASE,   0x001FE080
    .equ STACK_HI,     0x001FE08C
    .equ FAR_SSP,      0x00030000
    .equ PASS_SENT,    0xFFFF0000
    .equ PASS_VALUE,   0xC0FFEE00

_start:
    move.w  #0x2700, %sr
    move.l  #FAR_SSP, %sp
    move.l  #_aline_handler, 0x00000028   | vector 10 (A-line, 0x0a)
    move.l  #_irq_handler,   0x00000064   | vector 25 (autovector 1)
    clr.l   DEPTH_ADDR

    | MMU + dcache setup: low RAM copyback, ROM cacheable,
    | PASS/FAIL MMIO non-cacheable.  Same literals as the corrected
    | pea_aline_store_loss.s (true CM encodings, commit 645b526c).
    move.l  #0x000FE020, %d7
    movec   %d7, %dtt0
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    move.l  #0x00008000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr

    | Pre-fill the stack region (0x001FE060..0x001FE09F) with the
    | RAM-test stripe pattern -- this is the literal corruption
    | signature the HW ILA captured at 0x001FE080..0x001FE09C.
    lea     0x001FE060, %a0
    move.l  #STRIPE, %d6
    .rept 16
    move.l  %d6, (%a0)+
    .endr

    | Evict the cache line covering STACK_BASE (same 4 KiB same-set
    | stride idiom as pea_4x_cache_evict_once.s / pea_aline_store_loss.s).
    move.l  #0x12345678, %d5
    lea     0x001FF080, %a1
    move.l  %d5, (%a1)
    lea     0x00200080, %a1
    move.l  %d5, (%a1)
    lea     0x00201080, %a1
    move.l  %d5, (%a1)
    lea     0x00202080, %a1
    move.l  %d5, (%a1)
    lea     0x00203080, %a1
    move.l  %d5, (%a1)

    | Drop IPL to 0 so injected autovector-1 IRQs can preempt the
    | PEA->A-line window and the nested-handler storm below.
    move.w  #0x2000, %sr

    lea     STACK_HI, %a7
    pea     %pc@(_sent1)
    pea     %pc@(_sent2)
    pea     %pc@(_sent3)

    moveq   #5, %d3
_loop:
    move.w  %d3, %d0
    .short  0xA53D
    .short  0xA029
    subq.w  #1, %d3
    bne     _loop

    cmp.l   #STACK_BASE, %a7
    bne     _fail_a7

    move.l  (%a7)+, %d1
    cmp.l   #_sent3, %d1
    beq     _s3_ok
    cmp.l   #STRIPE, %d1
    beq     _fail_sent3_stripe
    bra     _fail_sent3_other
_s3_ok:

    move.l  (%a7)+, %d1
    cmp.l   #_sent2, %d1
    beq     _s2_ok
    cmp.l   #STRIPE, %d1
    beq     _fail_sent2_stripe
    bra     _fail_sent2_other
_s2_ok:

    move.l  (%a7)+, %d1
    cmp.l   #_sent1, %d1
    beq     _s1_ok
    cmp.l   #STRIPE, %d1
    beq     _fail_sent1_stripe
    bra     _fail_sent1_other
_s1_ok:

    cmp.l   #STACK_HI, %a7
    bne     _fail_a7

_pass:
    lea     PASS_SENT, %a0
    move.l  #PASS_VALUE, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_a7:
    move.l  #0xDEADA7A7, %d0
    bra     _fail_out
_fail_sent3_stripe:
    move.l  #0xDEAD3001, %d0
    bra     _fail_out
_fail_sent3_other:
    move.l  #0xDEAD3002, %d0
    bra     _fail_out
_fail_sent2_stripe:
    move.l  #0xDEAD2001, %d0
    bra     _fail_out
_fail_sent2_other:
    move.l  #0xDEAD2002, %d0
    bra     _fail_out
_fail_sent1_stripe:
    move.l  #0xDEAD1001, %d0
    bra     _fail_out
_fail_sent1_other:
    move.l  #0xDEAD1002, %d0
_fail_out:
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

| A-line handler (vector 0x0a).  Re-traps itself NEST_MAX-1 times
| (self-nested, via a second A-line opword executed inline) before
| unwinding -- mirrors the ROM handler's internal re-entry storm.
| Uses d6 + DEPTH_ADDR as scratch only; d6 is never touched by
| mainline code, so no save/restore is needed.  Because nothing else
| is pushed onto the stack beyond the hardware exception frame
| itself, 2(%sp) always addresses *this* invocation's own stacked PC
| field, regardless of nesting depth.
_aline_handler:
    addq.l  #1, DEPTH_ADDR
    move.l  DEPTH_ADDR, %d6
    cmp.l   #NEST_MAX, %d6
    bge     _al_fixup
    .short  0xA1F0                  | nested self-trap
_al_fixup:
    subq.l  #1, DEPTH_ADDR
    move.l  2(%sp), %d6
    addq.l  #2, %d6
    move.l  %d6, 2(%sp)
    rte

| Autovector-1 IRQ handler.  Minimal plausible work: push/pop a few
| regs, RTE.  Deliberately does NOT touch d3/d6/DEPTH_ADDR so it can
| safely preempt mainline OR the nested A-line handler at any point
| without perturbing either one's bookkeeping.
_irq_handler:
    movem.l %d0-%d1/%a0, -(%sp)
    move.l  #0x1, %d0
    move.l  #0x2, %d1
    movem.l (%sp)+, %d0-%d1/%a0
    rte

    .align 4
_sent1:
    nop
_sent2:
    nop
_sent3:
    nop
