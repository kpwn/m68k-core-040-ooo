| aline_triple_nested_dbf_storm.s -- Faithful sim repro of the REAL
| 3-level nested A-line exception structure discovered via live HW
| disassembly (2026-07-04 HW bisect session; see project memory
| project_lost_store_hw_bisect_2026_07_02.md, "TRUE NESTING SHAPE
| FOUND" section).  Every previous PEA/A-line stress test in this
| corpus (pea_aline_irq_storm*, aline_nested_in_handler_a7_drift,
| irq_during_aline_inject_phases, etc.) used a FLATTER or SELF-
| nested (same handler re-trapping itself) shape.  The real ROM glue
| routine at 0x4086AB9C is different: its first trap's OWN handler
| JSRs into a SEPARATE "device/memory-manager helper" subroutine
| containing 3 MORE independent A-line traps + a real conditional
| branch + a DBF byte-clear loop, before RTS-ing back so the outer
| loop's second trap can fire.  This file recreates that exact
| structure (not the exact ROM addresses/opcodes, which don't
| matter -- any top-nibble-0xA opword traps to vector 0x0a; only the
| NESTING SHAPE matters):
|
|   level 0 (glue routine, entered via JSR so a real caller-frame
|            return address sits on the stack under the routine's
|            own locals, exactly like the real ROM call site):
|     bra.s over an inline tag string (mirrors ROM's "SERD" stub)
|     4x PEA (local frame, 16 bytes)
|     4x { trap A -> recurses into device helper (JSR, not RTE) ->
|          trap B (flat, no further nesting) ->
|          pop one PEA'd value (loop control) }
|     rts  <-- the FATAL rts.  Pops from the CALLER's return-address
|              slot, which is cache-line-aligned (dcache LINE_BYTES
|              = 32 -- see rtl/core/mem/dcache.v:246) and untouched
|              by this routine's own 4 PEAs.
|
|   level 1 (trap A's dispatcher body -- a real vec 0x0a hardware
|            exception entry/exit wrapped around a JSR to the
|            device helper):
|     .short 0xA53D  (trap A opword)
|     dispatcher routes to a handler that does:
|         jsr _device_helper
|         <handler epilogue: fixup saved PC, rte>
|
|   level 2 (device helper -- its OWN 3 A-line traps are each an
|            independent full vec 0x0a hardware entry/exit, but the
|            helper itself is a plain subroutine reached via JSR,
|            not an exception -- exactly the newly-discovered shape):
|     .short 0xA055  (trap C)
|     tst.l %d4 / beq.s   (real conditional branch between traps)
|     .short 0xA440  (trap D)
|     .short 0xA522  (trap E)
|     moveq #39,%d1 / dbf byte-clear loop (40 iterations)
|     rts
|
| STACK_BASE = 0x001FE080 is the exact HW address (already 32-byte
| dcache-line aligned) where the lost store was observed on real
| silicon; reused verbatim here (not just "an aligned address") for
| maximum fidelity.  Region 0x001FE060-0x001FE09F is pre-filled each
| outer iteration with the literal DRAM-fill stripe pattern
| (0x6DB6DB6D) HW's dcache probe found sitting in the never-written
| line, so a genuine store loss surfaces as "popped back the stripe"
| rather than silently matching leftover valid data by accident.
|
| If the wedge reproduces, the fatal rts inside _glue_routine pops
| garbage instead of the real caller return address and the CPU
| wild-jumps into unmapped/non-code memory -- taking an unhandled
| fault (no bus-error handler installed here) that halts on a
| double bus fault, which the harness reports as FAIL (no
| +expect_dbl_fault is set, since here that outcome IS the bug, not
| an intended one).  A local-PEA-slot loss (rarer, but distinct from
| the final rts) is separately caught by the STRIPE compare right
| after each loop-control pop.
|
| See aline_triple_nested_dbf_storm_irq_sweep*.s/.args for the IRQ-
| phase-swept siblings (per project memory: async VIA-style IRQ
| timing landing inside this specific 3-level shape is suspected to
| be a necessary ingredient no prior flatter repro attempt could
| reach).

    .text
    .org 0

    .equ STRIPE,          0x6DB6DB6D
    .equ STACK_BASE,      0x001FE080   | exact HW lost-store address
    .equ CALL_SP,         0x001FE084   | SP just before jsr _glue_routine;
                                        | the jsr's own return-addr push
                                        | lands exactly at STACK_BASE.
    .equ FAR_SSP,         0x00030000
    .equ PASS_SENT,       0xFFFF0000
    .equ PASS_VALUE,      0xC0FFEE00
    .equ ITER_COUNT_ADDR, 0x000F0010
    .equ TARGET_ITERS,    100

_start:
    move.w  #0x2700, %sr
    move.l  #FAR_SSP, %sp
    move.l  #_aline_disp,  0x00000028   | vector 10 (A-line, 0x0a)
    move.l  #_irq_handler, 0x00000064   | vector 25 (autovector 1)
    clr.l   ITER_COUNT_ADDR

    | MMU + dcache setup: low RAM copyback, ROM cacheable, PASS/FAIL
    | MMIO non-cacheable.  Based on pea_aline_irq_storm.s's literals
    | (true CM encodings, commit 645b526c), but DTT0's mask is widened
    | from 0x0F to 0x4F (0x000FE020 -> 0x004FE020) so the DATA path
    | also transparently translates the 0x40xxxxxx test-binary/code
    | range, not just 0x00xxxxxx RAM.  This dispatcher (unlike
    | pea_aline_irq_storm.s's self-nested handler) reads each trap's
    | own opword back via a data-side load from the saved PC
    | (move.w (%a2),%d1) to route by opcode -- without this widened
    | DTT0, that load misses both DTTs, falls through to the (here
    | unconfigured) page-table walker, and the resulting bus-error
    | cascade wedges the whole test on an unrelated MMU-config gap
    | before the intended nested-storm mechanism ever gets exercised
    | (found + root-caused via bisection while authoring this test --
    | see the session notes for the minimal single-trap repro).
    move.l  #0x004FE020, %d7
    movec   %d7, %dtt0
    move.l  #0x400FE020, %d7
    movec   %d7, %itt0
    move.l  #0xFF00E060, %d7
    movec   %d7, %dtt1
    move.l  #0x00008000, %d7
    movec   %d7, %tc
    move.l  #0x80000000, %d7
    movec   %d7, %cacr

    | Drop IPL to 0 so injected autovector-1 IRQs (via .args +ipl=)
    | can preempt anywhere in the nested-trap storm below.
    move.w  #0x2000, %sr

_iter_loop:
    | Pre-fill the caller-frame + local-PEA stack region with the
    | literal HW stripe pattern before every call -- a genuine store
    | loss reads back as STRIPE, not as leftover valid data from a
    | prior iteration.
    lea     0x001FE060, %a0
    move.l  #STRIPE, %d6
    .rept   16
    move.l  %d6, (%a0)+
    .endr

    move.l  #CALL_SP, %sp
    jsr     _glue_routine
    | _glue_routine's rts lands us right back here.  If the rts
    | popped garbage (the STRIPE fill, or anything else), we do NOT
    | return here at all -- we wild-jump into an unmapped/non-code
    | address and the CPU takes an unhandled fault (double bus
    | fault -> cpu_halted -> harness FAIL) or the run times out.
    | Reaching this point at all is itself a strong PASS signal for
    | this iteration; the explicit check below additionally catches
    | a "landed somewhere plausible but subtly wrong" corruption.

    cmp.l   #CALL_SP, %sp
    bne     _fail_a7

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    lea     PASS_SENT, %a0
    move.l  #PASS_VALUE, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_a7:
    move.l  #0xDEADA7A7, %d0
    bra     _fail_out
_fail_out:
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

|--------------------------------------------------------------------
| Level 0: the glue routine.  Entered via JSR from _iter_loop, so a
| real caller return address sits at STACK_BASE (== CALL_SP - 4)
| before any of this routine's own code runs.
|--------------------------------------------------------------------
_glue_routine:
    bra.s   _glue_body
    .ascii  "SERD"
    .byte   0
    .even
_glue_body:
    pea     %pc@(_val1)
    pea     %pc@(_val2)
    pea     %pc@(_val3)
    pea     %pc@(_val4)

    moveq   #4, %d3
_glue_loop:
    .short  0xA53D              | trap A: recurses into device helper
    .short  0xA029              | trap B: flat, no further nesting

    move.l  (%sp)+, %d4         | pop one PEA'd value (loop control)
    cmp.l   #STRIPE, %d4
    beq     _fail_loop_stripe
    subq.l  #1, %d3
    bne     _glue_loop

    rts                          | THE fatal rts

_fail_loop_stripe:
    move.l  #0xDEADF00D, %d0
    bra     _fail_out

|--------------------------------------------------------------------
| Level 2: device/memory-manager helper.  Plain subroutine (JSR/RTS,
| not an exception) reached from trap A's handler.  Its own 3
| A-line traps are each a full, independent vec 0x0a entry/exit.
|--------------------------------------------------------------------
_device_helper:
    .short  0xA055               | trap C
    tst.l   %d4
    beq.s   _dh_skip
    nop
_dh_skip:
    .short  0xA440               | trap D
    .short  0xA522               | trap E

    lea     _clear_buf, %a1
    moveq   #39, %d1
_dh_clr_loop:
    clr.b   (%a1)+
    dbf     %d1, _dh_clr_loop
    rts

|--------------------------------------------------------------------
| Level 1: shared A-line dispatcher (vector 0x0a).  Routes by opword.
|--------------------------------------------------------------------
_aline_disp:
    movem.l %d1-%d2/%a1-%a2, -(%sp)
    movea.l 18(%sp), %a2
    move.w  (%a2), %d1
    addq.l  #2, %a2
    move.l  %a2, 18(%sp)

    cmp.w   #0xA53D, %d1
    beq     _handler_A
    cmp.w   #0xA029, %d1
    beq     _handler_B
    cmp.w   #0xA055, %d1
    beq     _handler_CDE
    cmp.w   #0xA440, %d1
    beq     _handler_CDE
    | else 0xA522 -> handler_CDE (also a safe fallback for anything
    | unexpected)
    bra     _handler_CDE

_handler_A:
    jsr     _device_helper
    bra     _aline_disp_return

_handler_B:
_handler_CDE:
    bra     _aline_disp_return

_aline_disp_return:
    movem.l (%sp)+, %d1-%d2/%a1-%a2
    rte

|--------------------------------------------------------------------
| Autovector-1 IRQ handler.  Deliberately avoids d3/d4/d6/a1 (the
| loop-control / device-helper scratch regs) so it can safely
| preempt mainline OR any nested level without perturbing bookkeeping.
|--------------------------------------------------------------------
_irq_handler:
    movem.l %d0-%d1/%a0, -(%sp)
    move.l  #0x1, %d0
    move.l  #0x2, %d1
    movem.l (%sp)+, %d0-%d1/%a0
    rte

    .align 4
_val1:
    nop
_val2:
    nop
_val3:
    nop
_val4:
    nop
_clear_buf:
    .space  64
