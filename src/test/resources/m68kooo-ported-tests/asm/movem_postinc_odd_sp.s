| movem_postinc_odd_sp.s — MOVEM.L (SP)+,d0-d3/a0-a3 from misaligned SP.
|
| Repro target: live FPGA pipeline wedge at PC=0x40809b84 inside L1
| IRQ handler epilogue.  ROM sequence:
|   40809b60: moveml d0-d3/a0-a3, -(sp)     ← handler entry: push 8 longs
|   ... handler body ...
|   40809b84: moveml (sp)+, d0-d3/a0-a3     ← handler epilogue: pop 8 longs
|   40809b88: rte
| Live state when wedged: A7=0x001fddae (word-aligned, not long-aligned).
| CPU pc_live frozen at
| 0x40809b84 across multiple JTAG samples; exc_count + pc-trace head
| also frozen.  Pipeline is stuck on the MOVEM.L pop from SP % 4 == 2.
|
| Single-long misaligned LOAD/STORE works (covered by tb-lsu directed
| tests + bsr_rts_odd_sp.s).  This test isolates whether MOVEM with an
| SP that is only word-aligned wedges the pipeline.
|
| If sim wedges (timeout) → repro confirmed; we have a uarch bug.
| If sim passes → wedge is a different bug (e.g. specific to A7 vs other
| address registers, or to the IRQ-handler context).
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.

    .text
    .org 0

    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | Set A7 to a known even base inside low RAM.
    move.l  #0x00100000, %a7

    | Plant 8 sentinel longs starting at A7 - 32 (the post-pop
    | location MOVEM.L (SP)+ will read from).  Use distinguishable
    | byte patterns so any byte-misshift surfaces in the result.
    move.l  #0xCAFE0000, 0x000FFFE0
    move.l  #0xCAFE0001, 0x000FFFE4
    move.l  #0xCAFE0002, 0x000FFFE8
    move.l  #0xCAFE0003, 0x000FFFEC
    move.l  #0xCAFE0004, 0x000FFFF0
    move.l  #0xCAFE0005, 0x000FFFF4
    move.l  #0xCAFE0006, 0x000FFFF8
    move.l  #0xCAFE0007, 0x000FFFFC

    | Make A7 word-aligned but not long-aligned.  After this
    | A7 = 0x000FFFDE, matching the live wedge's SP % 4 == 2 shape.
    | A7 + 32 = 0x000FFFFE (post-pop), still word-aligned.
    | The MOVEM here will read 8 misaligned longs:
    |   D0 ← bytes 0x000FFFDF..0x000FFFE2
    |   D1 ← bytes 0x000FFFE3..0x000FFFE6
    |   ...
    | The exact values depend on what's at 0x000FFFD0..0x000FFFDE
    | (initialized by the testbench RAM image — typically zero) and
    | the planted pattern.  We don't validate exact values here; we
    | only check that the instruction COMPLETES (no pipeline wedge).
    | A timeout indicates wedge.
    suba.l  #0x22, %a7                   | A7 = 0x000FFFDE (SP % 4 == 2)

    | The instruction under test.
    moveml  (%a7)+, %d0-%d3/%a0-%a3      | pop 8 misaligned longs

    | If we get here, the moveml completed — pipeline did not wedge.
    | Don't validate exact values: misaligned-long byte semantics on
    | 68040 are well-defined but the planted pattern depends on RAM
    | init.  Just prove the instruction RETIRES.
    move.l  #0x00100000, %a7             | restore SP
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
