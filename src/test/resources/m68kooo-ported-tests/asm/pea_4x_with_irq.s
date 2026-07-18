| pea_4x_with_irq.s — try to trigger store-loss with IRQ pressure.
|
| Same 4-PEA pattern but with IRQ injection during the sequence.
| Tests whether interrupt entry corrupts in-flight cracked-macro stores.

    .text
    .org 0

    .equ VBR_BASE, 0x00100000
    .equ STACK_HI, 0x00010120

_start:
    | Set up vector table.
    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr
    lea     VBR_BASE, %a1
    move.l  #_irq_handler, %d0
    | Install IRQ handlers for auto-vectors 25..31 (= vec 24..31).
    | Each vector occupies 4 bytes at VBR + vec*4.
    move.l  %d0, 100(%a1)                | vec 25 (IRQ1) at offset 0x64
    move.l  %d0, 104(%a1)
    move.l  %d0, 108(%a1)
    move.l  %d0, 112(%a1)
    move.l  %d0, 116(%a1)
    move.l  %d0, 120(%a1)
    move.l  %d0, 124(%a1)

    | Set CACR.DE=1.
    move.l  #0x80000000, %d0
    movec   %d0, %cacr

    | Pre-fill stack region cache line with stale data (mimics Mac OS).
    lea     0x00010100, %a0
    move.l  #0x4086AC12, (%a0)+
    move.l  #0x4086ABFA, (%a0)+
    move.l  #0x4086ABE2, (%a0)+
    move.l  #0x4086ABCA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+
    move.l  #0xAAAAAAAA, (%a0)+

    | Set up A7 for the PEAs.
    lea     STACK_HI, %a7

    | Lower SR.IPL to 0 so IRQs are unmasked.
    move.w  #0x2000, %sr                 | S=1, M=0, IPL=0

    | ── Arm an IRQ via debug-CSR ─────────────────────────────────────
    | OFF_IRQ_INJECT = 0x20 → write level 6 to fire IRQ6.
    | (Not used here — sim has no easy way to inject IRQs from m68k code.)
    | Instead, rely on natural VBL timer if any.  For sim, this test is
    | effectively the same as pea_4x_dcache_mmu unless IRQ injection works.

    | ── 4 consecutive PEA pattern, repeated 100 times to maximise IRQ-hit ─
    move.l  #100, %d4
_pea_loop:
    lea     STACK_HI, %a7
    pea     %pc@(_t1)
    pea     %pc@(_t2)
    pea     %pc@(_t3)
    pea     %pc@(_t4)

    | Verify
    cmp.l   #STACK_HI-16, %a7
    bne     _fail_a7
    move.l  0(%a7), %d0
    cmp.l   #_t4, %d0
    bne     _fail_s0
    move.l  4(%a7), %d0
    cmp.l   #_t3, %d0
    bne     _fail_s1
    move.l  8(%a7), %d0
    cmp.l   #_t2, %d0
    bne     _fail_s2
    move.l  12(%a7), %d0
    cmp.l   #_t1, %d0
    bne     _fail_s3

    | Re-fill stale pattern between iterations.
    lea     0x00010100, %a0
    move.l  #0x11111111, (%a0)+
    move.l  #0x22222222, (%a0)+
    move.l  #0x33333333, (%a0)+
    move.l  #0x44444444, (%a0)+
    subq.l  #1, %d4
    bne     _pea_loop

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_a7:
    move.l  #0xDEAD00A7, %d7
    bra     _fail
_fail_s0:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail_s1:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail_s2:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail_s3:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_irq_handler:
    rte

    .align 4
_t1: nop
_t2: nop
_t3: nop
_t4: nop
