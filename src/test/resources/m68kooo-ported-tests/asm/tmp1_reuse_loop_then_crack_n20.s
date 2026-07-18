| tmp1_reuse_loop_then_crack_n20.s -- NEW hypothesis repro (2026-07-05): a
| tight, MULTI-ITERATION loop that reuses the hidden scratch register
| REG_TMP1 (uop_pkg.v REG_TMP1 = 5'd17) via repeated memory-to-memory
| MOVE.B, executing IMMEDIATELY before the `move (sp)+,sr` TMP1 crack
| under investigation (see project_via1_lockstep_deepdive.md, "A7/SR
| corruption: RAT-tag bug CONFIRMED (Case 1)" + all `move_sp_postinc_
| sr_tmp1` siblings in this directory).
|
| Motivation: a live HW ILA capture of the REAL ROM boot sequence
| around this crack (ROM PC 0x408701AE, the `.Sony` floppy driver GCR-
| decode-then-restore epilogue) shows the code immediately preceding
| it in program order is ALSO a REG_TMP1 user -- disassembly of
| files/420dbff3.rom confirms:
|
|     408700f4: movew  #64,%d6
|     408700f8: moveb  %a2@,%a2@        <-- memory-to-memory MOVE.B,
|                                            cracks to LOAD (A2)->TMP1 /
|                                            STORE TMP1->(A2) / ALU_TST
|                                            TMP1 (decode_uop_assemble.v
|                                            ~16677-16785, the
|                                            `move_regular_supported &&
|                                            src_is_memory && !src_is_
|                                            indexed && dst_is_memory`
|                                            arm) -- SAME REG_TMP1 the
|                                            `move (sp)+,sr` crack's own
|                                            phase 0 (LOAD (An)->TMP1)
|                                            targets.
|     408700fa: moveb  %a3@,%d4
|     408700fc: dbmi   %d6,0x408700f8   <-- tight IWM-poll timeout loop,
|                                            retires the moveb above MANY
|                                            times back-to-back with a
|                                            MINIMAL 2-instruction body.
|
| No prior synthetic repro in this corpus constructed this specific
| adjacency: a multi-iteration loop that ITSELF hammers REG_TMP1,
| landing directly on the crack's own TMP1 rename with essentially zero
| intervening pipeline drain time.  This is explicitly NOT a repeat of
| the already-ruled-out rounds (2-wide interleaving, IRQ storms,
| single-shot F-line exceptions in isolation, branch-misprediction
| checkpoint rollback, BRAM timing, debug-overlay flush) -- it targets a
| genuinely new hypothesis: back-to-back SAME-hidden-register reuse
| under tight loop pressure, immediately adjacent to the crack under
| test.
|
| Loop shape: `move.b (%a2),(%a2)` (mode-2 An-indirect on BOTH sides,
| byte-exact match for the ROM's `moveb %a2@,%a2@`) inside a `dbf`
| loop with a 2-instruction body (mirrors the ROM's moveb+dbmi tightness
| -- dbf is used instead of dbmi/dbcc-on-data-condition so the
| iteration count is deterministic and sweepable via LOOP_COUNT without
| needing a live IWM-shaped data source).  TARGET_LOOP_ITERS is a build-
| time equ swept 5/10/20 by the .args-selected sibling invocations (see
| tmp1_reuse_loop_then_crack.args and the _n5/_n20/_bubble variants).
|
| State-match notes (best-effort, per live-HW ILA `ila_supervisor_mode`
| etc column values quoted in the task brief): supervisor mode = 1
| throughout (this test never drops to user mode), IPL unmasked (S=1,
| ipl=0, matching the SR_PATTERN used by every sibling TMP1-crack test
| in this directory) so the crack executes under the same interrupt-
| unmasked condition the real ROM epilogue does.
|
| PASS/FAIL contract matches the sibling tests exactly: capture SR
| immediately after the crack (before any flag-setting instruction),
| check it against the known pushed pattern; also check SP has not
| drifted.  A TMP1 RAT-mistag would show up here as a corrupted SR
| readback (the crack's phase 2 SYS_MOVE_SR reading a stale/wrong
| physical register instead of the freshly-loaded TMP1 value).

    .text
    .org 0

    .equ INIT_SSP,        0x00018000
    .equ PASS_SENT,       0xFFFF0000
    .equ ITER_COUNT_ADDR, 0x000F0000
    .equ SCRATCH_ADDR,    0x000F0020
    .equ TARGET_ITERS,    400
    .equ SR_PATTERN,      0x2000        | S=1, ipl=0 -- keeps all IRQ levels unmasked, matches sibling tests

    | LOOP_COUNT is overridden per sibling test file (this base variant
    | uses 10, the middle of the 5-20 range named in the task brief).
    .equ LOOP_COUNT,      20

_start:
    move.w  #0x2700, %sr
    move.l  #INIT_SSP, %sp
    clr.l   ITER_COUNT_ADDR
    move.b  #0x5A, SCRATCH_ADDR   | seed value; the mem-to-mem move is a
                                    | same-address dummy R/W (matches the
                                    | ROM's %a2@,%a2@ pattern) so this
                                    | byte is simply read back and
                                    | rewritten every iteration.

    | Drop to SR_PATTERN via the SAME crack we're testing, so the very
    | first iteration is exercised identically to the rest (matches
    | sibling tests' convention).
    move.w  #SR_PATTERN, -(%sp)
    move    (%sp)+, %sr

_iter_loop:
    | Record SP before this iteration so we can catch any A7 drift.
    move.l  %sp, %d1

    | THE TMP1-REUSE LOOP: LOOP_COUNT back-to-back memory-to-memory
    | MOVE.B crack instances, minimal 2-instruction body, immediately
    | preceding the crack under test below -- byte-exact adjacency match
    | for the real ROM's 408700f8/408700fc dbmi-loop shape.
    lea     SCRATCH_ADDR, %a2
    move.w  #(LOOP_COUNT-1), %d6
_tmp1_loop:
    move.b  (%a2), (%a2)          | mem-to-mem MOVE.B -> LOAD/STORE/TST
                                    | crack sharing REG_TMP1 (uop_pkg.v
                                    | 5'd17) with the crack below
    dbf     %d6, _tmp1_loop

    | THE CRACK UNDER TEST, immediately after the TMP1-reuse loop's last
    | iteration retires -- zero intervening instructions, matching the
    | task's "immediately followed by" adjacency requirement.
    move.w  #SR_PATTERN, -(%sp)   | push known SR pattern
    move    (%sp)+, %sr            | THE CRACK UNDER TEST (3 uops, TMP1)

    | Capture SR into D0 IMMEDIATELY after the crack, before ANY
    | flag-setting instruction (see sibling tests' header note on CMP's
    | own Z-flag side effect).
    move    %sr, %d0

    cmp.l   %d1, %sp
    bne     _fail_sp_drift

    cmp.w   #SR_PATTERN, %d0
    bne     _fail_sr_corrupt

    addq.l  #1, ITER_COUNT_ADDR
    move.l  ITER_COUNT_ADDR, %d0
    cmp.l   #TARGET_ITERS, %d0
    blt     _iter_loop

    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_sp_drift:
    | D1 = expected SP, current %sp = actual (drifted) SP.
    move.l  %sp, PASS_SENT
    bra     .

_fail_sr_corrupt:
    | D0 = the corrupted SR readback.  Sentinel carries it directly so
    | a TMP1-mistag shows up as a recognizable garbage value (e.g. a
    | stale phys-reg's data instead of SR_PATTERN).
    move.l  %d0, PASS_SENT
    bra     .
