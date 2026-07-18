| fpu_faddb_tight_bypass_repro.s — task #108 follow-up #2: exact-fidelity
| repro of the real ROM loop at 0x4088e568-0x4088e592 (FPSP decimal-
| conversion routine), byte-for-byte copied opcodes, with ZERO filler
| between BFEXTU and FADD.B.
|
| fpu_faddb_dbf_loop_repro.s (task #108) inserted 8 filler integer ops
| between BFEXTU and FADD.B to force the "producer long-retired before
| consumer dispatches" condition — that variant PASSES in sim even with
| the disp_src_b_rdy domain-mismatch fix applied.  But the REAL ROM has
| NO filler: BFEXTU (0x4088e57a) and FADD.B (0x4088e57e) are immediately
| adjacent.  This means real hardware relies on the SAME-CYCLE (or
| near-same-cycle) int-CDB-to-iq_fp bypass working correctly every single
| iteration — a narrower, tighter timing window than the dispatch-time
| ready-bit case the existing fix addresses.  If this tight-adjacency
| shape passes in sim too, the bug is likely a bypass-mux/wakeup-timing
| defect that RTL sim's own cycle model doesn't trigger (would need
| ILA); if it FAILS in sim, we have a new, sim-reproducible root cause.
|
| Instruction bytes are copied directly from files/420dbff3.rom at
| 0x4088e568-0x4088e592 (objdump-verified) to maximize fidelity to the
| exact real-hardware shape, including the dynamic-offset BFEXTU
| (offset=D3, width=#4, src=D4, dst=D0) and the trailing ADDQ.B/DBF pair.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F02 — unexpected re-trap

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00018000, %a7
    move.l  #_fline_handler, 0x0000002C   | vec 11 (F-line)

    | Genuine synchronous F-line trap — matches how the ROM actually
    | reaches this FPSP code via real exception dispatch.
    .short  0xFFFF

    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F02, %d2
    move.l  %d2, (%a1)
_bad_fallthrough:
    bra     _bad_fallthrough

_fline_handler:
    | Seed state matching the ROM's context at 0x4088e560-0x4088e568:
    | D0 already holds a byte value, FP0 already has a prior conversion
    | result.  ROM's own preceding context is opaque BCD-table work we
    | don't need to replicate exactly — we only need FP0/D0/D4/D3/D2 in
    | plausible states before the loop, matching what the earlier
    | filler-based repro already validated as sufficient for the shape.
    move.l  #0x00000000, %d0
    .short  0xF200, 0x4000              | fmoves %d0,%fp0

    move.l  #0x00000001, %d0             | matches ROM's own D0 seed byte
    .short  0xF200, 0x5822              | faddb %d0,%fp0   (0x4088e568)

    move.l  #0x76543210, %d4            | source nibbles for bfextu
    moveq   #0, %d3
    moveq   #7, %d2

    | --- exact copy of ROM bytes 0x4088e574-0x4088e584, zero filler ---
_loop:
    .short  0xF23C, 0x5823, 0x000A      | fmulb #10,%fp0    (0x4088e574)
    .short  0xE9C4, 0x08C4              | bfextu %d4,%d3,4,%d0 (0x4088e57a)
    .short  0xF200, 0x5822              | faddb %d0,%fp0    (0x4088e57e)
    .short  0x5803                      | addqb #4,%d3      (0x4088e582)
    dbf     %d2, _loop                  | (0x4088e584)
    | --- end exact copy ---

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt
