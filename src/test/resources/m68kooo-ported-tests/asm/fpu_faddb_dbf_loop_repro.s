| fpu_faddb_dbf_loop_repro.s — reproduce the exact ROM FPSP decimal-conversion
| loop shape found live-locking real hardware after the EA-source FPU decode
| matrix landed (task #107): an 8-iteration DBF loop repeatedly issuing
| FMUL.B #imm,FP0 and FADD.B Dn,FP0 back-to-back.
|
| Root cause (found via code review, task #108): m68k_core_issue.vh wired
| iq_fp's `disp_src_b_rdy` to the FP-side ready bit (`fp_rat_psb_rdy`) even
| for the new int-sourced-B dyadic class (`disp_is_int_src_b`), instead of
| mirroring the existing `disp_src_a_rdy` ternary that correctly selects
| the INT RAT's ready bit for int-typed sources.  This meant an iq_fp entry
| whose B operand (the raw int value BFEXTU produced) was already resident
| in the int PRF before the FADD dispatched would show as permanently
| NOT ready — the entry could only wake via an exact-same-cycle CDB bypass
| at dispatch, never via the wakeup path (which does correctly check the
| int CDBs), causing a genuine, permanent issue-queue stall on real
| hardware.  A tight back-to-back loop in isolation can accidentally catch
| the lucky same-cycle bypass every time; this version deliberately
| inserts filler integer ops (with independent register churn keeping the
| int CDBs busy with unrelated traffic) between BFEXTU and FADD.B so the
| producing value is provably long-retired before the consumer dispatches
| — exactly the condition that hung on hardware.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 — vec-11 F-line trap (should never happen — this is the
|                already-covered shape)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    | Seed FP0 with a real single conversion (matches ROM's fmoveb #0,%fp0
    | shape closely enough via FMOVE.S Dn,FPn — value doesn't matter here).
    move.l  #0x00000000, %d0
    .short  0xF200, 0x4000              | FMOVE.S D0,FP0

    move.l  #0x76543210, %d4            | source nibbles for bfextu
    moveq   #0, %d3
    moveq   #7, %d2
    moveq   #0, %d5
_loop:
    .short  0xF23C, 0x5823, 0x000A      | FMUL.B #10,FP0
    bfextu  %d4{%d3:#4}, %d0            | matches ROM's e9c4 08c4
    | Filler: unrelated integer churn to push D0's producing CDB broadcast
    | well behind us before FADD.B dispatches, and to keep the int CDBs
    | busy with traffic that has nothing to do with D0's tag.
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    addq.l  #1, %d5
    .short  0xF200, 0x5822              | FADD.B D0,FP0
    addq.b  #4, %d3
    dbf     %d2, _loop

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_hf:
    bra     _hf
