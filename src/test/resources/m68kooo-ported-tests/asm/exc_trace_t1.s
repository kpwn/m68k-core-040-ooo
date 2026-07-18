| exc_trace_t1.s — single-step trace via SR.T1, vec 9, fmt-2 frame.
|
| Goal: with SR.T1 (bit 15) set, EVERY instruction takes a trace
| exception after retire.  Verify:
|   1. After exactly one user instruction executes, vec 9 fires.
|   2. The pushed stack frame is format 2 (12 bytes — 8 + 4 for
|      the address-of-faulting-instruction longword).
|   3. The handler can clear T1 via the SR slot in the frame and
|      RTE; control resumes at the instruction AFTER the traced one.
|
| Construction:
|   - Install vec-9 handler that increments a counter, clears T1 in
|     the stacked SR, and RTEs.
|   - Set SR := 0xA000 (S=1, T1=1, IPL=0).
|   - Execute one NOP — this should retire then trace-trap.
|   - Handler fires once; clears T1; resumes.
|   - Mainline continues (no more traces) and writes PASS once
|     counter == 1.
|
| Note: the testbench finishes on ANY write to PASS_SENT (0xFFFF0000).
| Sentinel value 0xC0FFEE00 = PASS, anything else = FAIL.  Therefore
| we MUST NOT write to PASS_SENT until we are ready to declare PASS or
| FAIL — no pre-writes to that address.
|
| PASS sentinel: 0xC0FFEE00 (counter == 1 after RTE).
| FAIL sentinels:
|   0xDEAD0C01 — counter != 1 (trace fired wrong number of times)
|   0xDEAD0C02 — frame format word mismatch (not fmt-2)

    .text
    .org 0

    .equ PASS_SENT,  0xFFFF0000
    .equ COUNTER,    0x00000400
    .equ FRAME_FMT,  0x00000404

_start:
    lea     0x00010000, %a7
    move.l  #_trace_h, 0x00000024       | vec 9 (trace) @ 0x24
    move.l  #0, COUNTER.l
    move.l  #0, FRAME_FMT.l

    | Set T1 = 1 via MOVE.W #imm,SR.  T1 is bit 15 of SR; S=bit 13;
    | IPL = bits 10:8.  We want S=1, T1=1, IPL=0 -> 0xA000.
    move.w  #0xA000, %sr

    | One traced instruction.
    nop

    | If trace fired exactly once, COUNTER==1 and we get here with
    | T1 cleared (handler did it).  If trace did NOT fire, we still
    | reach here but COUNTER==0.
    move.l  COUNTER.l, %d0
    cmp.l   #1, %d0
    bne     _fail_count

    | Verify frame format word == 0x2024 (fmt-2 nibble + vec 9 byte off).
    | FRAME_FMT was written with ext.l of the 16-bit format word, so
    | the upper 16 bits are 0 and we just compare the full 32-bit value.
    move.l  FRAME_FMT.l, %d0
    cmp.l   #0x00002024, %d0
    bne     _fail_fmt

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_count:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0C01, %d2
    move.l  %d2, (%a1)
_halt_fc:
    bra     _halt_fc

_fail_fmt:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0C02, %d2
    move.l  %d2, (%a1)
_halt_ffmt:
    bra     _halt_ffmt

_trace_h:
    | Increment counter.
    addq.l  #1, COUNTER.l
    | Capture format word — at SP+6 for fmt-0/2 layouts.
    move.w  6(%a7), %d0
    ext.l   %d0
    move.l  %d0, FRAME_FMT.l
    | Clear T1 in the stacked SR (SP+0) so the RTE returns with T1=0.
    move.w  (%a7), %d0
    andi.w  #0x7FFF, %d0
    move.w  %d0, (%a7)
    rte
