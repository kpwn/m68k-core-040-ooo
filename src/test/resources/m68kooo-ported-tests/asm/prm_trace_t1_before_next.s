| prm_trace_t1_before_next.s — T1 trace fires before next instruction.
|
| Spec: M68040 User's Manual, tracing and trace exception processing.
| With SR.T1 set, a trace exception is taken after one instruction
| retires and before the following instruction executes.

    .text
    .org 0

    .equ COUNTER, 0x00000440

_start:
    lea     0x00010000, %a7
    move.l  #_trace_handler, 0x00000024
    move.l  #0, COUNTER.l

    move.w  #0xA000, %sr        | T1=1, S=1
    addq.l  #1, COUNTER.l       | traced instruction
    addq.l  #2, COUNTER.l       | must not execute before handler

_after_trace:
    move.l  COUNTER.l, %d0
    cmp.l   #1, %d0
    bne     _fail1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail

_trace_handler:
    | The point of this test is: trace fires AFTER addq #1 (COUNTER==1)
    | and BEFORE addq #2 runs.  If we see COUNTER==1 here, that proves
    | the second addq did NOT pre-execute.  Write PASS sentinel from
    | inside the handler and halt — RTEing back would let the second
    | addq run and clobber COUNTER to 3, which the original outer
    | `cmp.l #1, %d0` check (still there as a safety net) would then
    | mis-flag.  This test isn't about RTE semantics; it's about trace
    | priority at the macro boundary.
    move.l  COUNTER.l, %d0
    cmp.l   #1, %d0
    bne     _handler_fail
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt_handler_pass:
    bra     _halt_handler_pass

_handler_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d7
    move.l  %d7, (%a0)
_halt_hfail:
    bra     _halt_hfail
