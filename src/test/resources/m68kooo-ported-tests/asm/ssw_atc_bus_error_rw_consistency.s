| ssw_atc_bus_error_rw_consistency.s — commit-review item #5 regression.
|
| Bug: commit.v's deferred AXI-BRESP store-fault path (fed by lsu.v's
| `dc_bresp != 2'b00`, i.e. a genuine bus-level SLVERR/DECERR with zero
| MMU involvement) hardwired SSW.ATC=1 unconditionally, while the
| synchronous read-fault path correctly set ATC=0 for the same class of
| physical/unmapped-address fault.  That was an inconsistency: a plain
| bus error should report ATC=0 (unresolvable — "physical", not an
| MMU/ATC-detected translation fault) regardless of access direction.
| Genuine WP/MMU-detected faults (which SHOULD get ATC=1) go through a
| completely different, synchronous path fed by the walker's
| mmu_fault_in — see mmu_wp_fix_rte.s / mmu_wp_user_vs_super.s.
|
| This test drives BOTH directions at the same unmapped address and
| checks the SSW.ATC bit reads 0 in both cases, catching a regression
| to the old read/write-inconsistent hardwiring in either direction.
|
| No MMU setup at all in this test (MMU stays disabled throughout) —
| every access here reaches the physical bus untranslated, so both
| faults are unambiguously "physical bus error", never ATC faults.
| Both handlers stay in supervisor mode throughout (SR.S is never
| cleared), so each handler can just discard its own Format-7 frame
| (60 bytes) with a plain LEA and fall through to the next phase —
| no RTE / frame-field surgery needed.
|
| PASS: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0601 — READ fault reported ATC=1 (should be 0)
|   0xDEAD0602 — WRITE fault reported ATC=1 (should be 0, this is the
|                bug commit-review item #5 fixed)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ UNMAPPED,  0xAAAA0000
    .equ FMT7_SIZE, 60

_start:
    lea     0x00010000, %a7
    move.l  #_write_handler, 0x00000008   | vec 2 (bus error) — write phase

    | ── Phase 0: WRITE bus error ────────────────────────────────────
    lea     UNMAPPED, %a0
    move.w  #0x1234, (%a0)                 | SLVERR on write → vec 2

    | Unreachable — handler diverts straight into phase 1.
_fail_unreached_w:
    lea     PASS_SENT, %a1
    move.l  #0xDEADFA17, %d1
    move.l  %d1, (%a1)
_halt_fail_w:
    bra     _halt_fail_w

_write_handler:
    | Check ATC bit (10) of the SSW at A7+12 — must be 0.
    move.w  12(%a7), %d0
    btst    #10, %d0
    bne     _fail_write_atc

    | Discard this Format-7 frame and fall into phase 1 directly —
    | still supervisor (S was never cleared), so no RTE is needed.
    lea     FMT7_SIZE(%a7), %a7
    move.l  #_read_handler, 0x00000008
    bra     _phase1

_phase1:
    | ── Phase 1: READ bus error ─────────────────────────────────────
    lea     UNMAPPED, %a0
    move.w  (%a0), %d1                     | SLVERR on read → vec 2

_fail_unreached_r:
    lea     PASS_SENT, %a1
    move.l  #0xDEADFA18, %d1
    move.l  %d1, (%a1)
_halt_fail_r:
    bra     _halt_fail_r

_read_handler:
    move.w  12(%a7), %d0
    btst    #10, %d0
    bne     _fail_read_atc

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_write_atc:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0602, %d2
    move.l  %d2, (%a1)
_halt_fwa:
    bra     _halt_fwa

_fail_read_atc:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0601, %d2
    move.l  %d2, (%a1)
_halt_fra:
    bra     _halt_fra
