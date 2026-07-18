| unaligned_long_vector_dispatch.s
| =============================================================================
| Targeted reproduction of the live-FPGA Q700 timer-test divergence
| where a `move.l %a3,(%a2)` with %a2=0xFF32 (UNALIGNED LONG) installs
| a vector handler and then `exception.v` reads back the same address
| for vector dispatch.  Snap from the bisect harness showed the handler
| ran 6 of 61 times — most IRQ entries diverged.
|
| Two distinct paths must be coherent on an unaligned LONG bus:
|   1. LSU split-LONG store     (rtl/core/mem/lsu.v + m68k_mem_lane.vh)
|   2. exception.v split-LONG vector read
|      (S_READ_VECTOR_R / _R2 / m68k_mem_split_rdata)
| Both share the dcache port, muxed via `exc_active` in
| rtl/core/m68k_core_memory.vh.  If the two helpers don't agree on the
| byte-lane order produced by a misaligned address, the handler PC the
| sequencer reconstructs will be a permuted version of what the program
| actually wrote.
|
| -----------------------------------------------------------------------------
| What this test does
| -----------------------------------------------------------------------------
| Phase A — LSU round-trip (no exceptions involved):
|   * Stores known LONG values to four offsets-by-1 of a base address
|     in writable RAM (NOT the low-mem 0xFF32 region the live ROM hits;
|     the testbench may treat 0x0..0x3FF specially), each via
|     `move.l Dn,(An)`:
|         off=0 (aligned, 0x0001FF30)
|         off=1            (0x0001FF31)
|         off=2            (0x0001FF32)  ← the live-FPGA case
|         off=3            (0x0001FF33)
|   * Reads each back via `move.l (An),Dm` and CMP-compares.
|   * Any mismatch writes a non-PASS sentinel and halts.
|
| Phase B — exception read-side coherency:
|   * Sets VBR to 0x0001FECE (an UNALIGNED VBR — note that a real Q700
|     ROM would never do this; we want to exercise the split path
|     deterministically).  Slot for vec 33 (TRAP #1) lives at
|     VBR + 33*4 = 0x0001FECE + 0x84 = 0x00020052 — which is itself
|     UNALIGNED (off=2).  Installs handler addr there via the same
|     `move.l Dn,(An)` path Phase A validated.
|   * Triggers TRAP #1 — exception.v must read 0x00020052 with its
|     own split-LONG sequencer and dispatch to our handler.
|   * Handler writes a marker, RTEs.
|   * Tail check: the marker must equal the handler's _start address.
|
| Phase C — tight install→fire window:
|   * Re-points vec 34 (TRAP #2) to a DIFFERENT handler.  The store and
|     the TRAP are separated by only ~3 instructions, so the store has
|     to commit (and exception.v has to see the new value via dcache,
|     not stale BRAM) before the TRAP entry sequencer reads it.  This
|     mirrors the live-FPGA timing where the ROM's install→arm window
|     is short.
|
| -----------------------------------------------------------------------------
| Pass / Fail
| -----------------------------------------------------------------------------
| PASS: all four LSU round-trips match, both TRAP handlers ran, post
|       checks succeed → write 0xC0FFEE00 to 0xFFFF0000.
| FAIL: any mismatch / wrong handler entry → write 0xDEAD00xx with
|       a phase-specific suffix to 0xFFFF0000 so the failure mode is
|       diagnosable from the testbench printout alone.
|
| Failure codes:
|   0xDEAD0010 — Phase A off=0 mismatch
|   0xDEAD0011 — Phase A off=1 mismatch
|   0xDEAD0012 — Phase A off=2 mismatch (the live-FPGA scenario)
|   0xDEAD0013 — Phase A off=3 mismatch
|   0xDEAD0020 — Phase B handler never ran (vector dispatch wrong)
|   0xDEAD0021 — Phase B handler ran but marker mismatched
|   0xDEAD0030 — Phase C handler never ran (tight-window failure)
|   0xDEAD0031 — Phase C marker mismatched
| =============================================================================

    .text
    .org 0

    .equ    PASS_SENT,   0xFFFF0000
    .equ    BASE,        0x0001FF30        | aligned base, then off=0..3
    .equ    PATTERN0,    0xCAFEBABE
    .equ    PATTERN1,    0x12345678
    .equ    PATTERN2,    0x40847D06        | mirrors live-FPGA handler PC
    .equ    PATTERN3,    0xDEADC0DE
    .equ    VBR_BASE,    0x0001FECE        | unaligned VBR (off=2)
    .equ    VEC33_OFF,   0x00000084        | 33*4
    .equ    VEC34_OFF,   0x00000088        | 34*4
    .equ    MARKER_B,    0x000200C0        | scratch in RAM
    .equ    MARKER_C,    0x000200C4
    .equ    USP_BASE,    0x00040000
    .equ    SSP_BASE,    0x00080000

_start:
    | We boot supervisor mode, SR=0x2700, A7 is SSP.
    lea     SSP_BASE, %a7

    | -----------------------------------------------------------------
    | Phase A — LSU round-trip across off=0..3
    | -----------------------------------------------------------------
    | off=0 (aligned)
    move.l  #BASE, %a0
    move.l  #PATTERN0, %d0
    move.l  %d0, (%a0)
    move.l  (%a0), %d1
    cmp.l   %d0, %d1
    bne     _fail_A0

    | off=1
    move.l  #BASE+1, %a0
    move.l  #PATTERN1, %d0
    move.l  %d0, (%a0)
    move.l  (%a0), %d1
    cmp.l   %d0, %d1
    bne     _fail_A1

    | off=2 (the live-FPGA case)
    move.l  #BASE+2, %a0
    move.l  #PATTERN2, %d0
    move.l  %d0, (%a0)
    move.l  (%a0), %d1
    cmp.l   %d0, %d1
    bne     _fail_A2

    | off=3
    move.l  #BASE+3, %a0
    move.l  #PATTERN3, %d0
    move.l  %d0, (%a0)
    move.l  (%a0), %d1
    cmp.l   %d0, %d1
    bne     _fail_A3

    | -----------------------------------------------------------------
    | Phase B — exception read-side coherency
    | -----------------------------------------------------------------
    | Set VBR to an unaligned base.
    move.l  #VBR_BASE, %d0
    movec   %d0, %vbr

    | Install vec 33 handler at VBR + 0x84.  This address is itself
    | unaligned (VBR_BASE | 0x84 = 0x00020052), so it exercises the
    | split-LONG store on the install side AND the split-LONG read on
    | the exception side.
    move.l  #_h_vec33, %a0       | data: handler PC
    move.l  #VBR_BASE, %a1
    add.l   #VEC33_OFF, %a1      | now %a1 = 0x00020052
    move.l  %a0, (%a1)

    | Pre-clear the markers so we can tell whether the handler ran.
    move.l  #0, MARKER_B
    move.l  #0, MARKER_C

    | Fire TRAP #1 → exception.v must read VBR+0x84 (split LONG) and
    | jump to _h_vec33.  RTE returns here.
    trap    #1

    | Verify Phase B
    move.l  MARKER_B, %d2
    cmp.l   #0xB0B0B0B0, %d2
    bne     _fail_B_check        | 0 → handler never ran;
                                 | other  → handler ran but wrong write

    | -----------------------------------------------------------------
    | Phase C — tight install→fire window
    | -----------------------------------------------------------------
    | Re-aim vec 34 at a different handler with as few intervening
    | instructions as possible — this is the live-FPGA timing pattern.
    move.l  #_h_vec34, %a0
    move.l  #VBR_BASE, %a1
    add.l   #VEC34_OFF, %a1      | %a1 = 0x00020056 (off=2 again)
    move.l  %a0, (%a1)
    trap    #2                   | install → fire, ~3 inst gap

    | Verify Phase C
    move.l  MARKER_C, %d2
    cmp.l   #0xC1C1C1C1, %d2
    bne     _fail_C_check

    bra     _pass

| =============================================================================
| Failure paths — each writes a unique sentinel so the failing scenario
| is identifiable without a waveform.
| =============================================================================
_fail_A0:
    move.l  #0xDEAD0010, %d0
    bra     _fail_write
_fail_A1:
    move.l  #0xDEAD0011, %d0
    bra     _fail_write
_fail_A2:
    move.l  #0xDEAD0012, %d0
    bra     _fail_write
_fail_A3:
    move.l  #0xDEAD0013, %d0
    bra     _fail_write
_fail_B_check:
    | Distinguish "never ran" (marker == 0) from "ran but wrong write".
    move.l  MARKER_B, %d3
    cmp.l   #0, %d3
    bne     _fail_B_marker
    move.l  #0xDEAD0020, %d0
    bra     _fail_write
_fail_B_marker:
    move.l  #0xDEAD0021, %d0
    bra     _fail_write
_fail_C_check:
    move.l  MARKER_C, %d3
    cmp.l   #0, %d3
    bne     _fail_C_marker
    move.l  #0xDEAD0030, %d0
    bra     _fail_write
_fail_C_marker:
    move.l  #0xDEAD0031, %d0
    bra     _fail_write

_fail_write:
    lea     PASS_SENT, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_pass:
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, (%a0)
_halt_pass:
    bra     _halt_pass

| =============================================================================
| TRAP #1 handler — Phase B
| =============================================================================
_h_vec33:
    move.l  #0xB0B0B0B0, %d4
    move.l  %d4, MARKER_B
    rte

| =============================================================================
| TRAP #2 handler — Phase C
| =============================================================================
_h_vec34:
    move.l  #0xC1C1C1C1, %d4
    move.l  %d4, MARKER_C
    rte
