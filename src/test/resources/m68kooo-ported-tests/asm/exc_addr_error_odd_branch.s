| exc_addr_error_odd_branch.s — odd control-transfer target raises vec 3
|
| On the 68040 the PROGRAM COUNTER must always be even.  Any taken
| control transfer whose target has bit 0 set — JMP/JSR with a computed
| odd EA, a branch with an odd displacement, RTS popping an odd return
| address, DBcc with an odd displacement — must raise Address Error
| (vector 3) instead of executing at the odd address (M68040UM §8.2.3).
| Data accesses are exempt (040 handles misaligned operands
| transparently — see exc_addr_error.s, which this test complements).
|
| Reference note: Musashi as configured in this repo compiles address-
| error emulation OUT (M68K_EMULATE_ADDRESS_ERROR=OFF in m68kconf.h) and
| happily executes from odd PCs (empirically verified 2026-07-15 with a
| JMP-to-odd probe: the odd-stream code ran, no vec 3).  Even with the
| option ON its frame builder is 68000-only.  So the frame layout
| asserted here follows the M68040UM directly:
|   format $2 (12-byte) frame, vector offset 0x00C:
|     SP+0  : SR
|     SP+2  : PC        = address of the transfer instruction
|                         (re-exec-after-fix semantics)
|     SP+6  : 0x200C    (format $2, vec 3 → offset 0x00C)
|     SP+8  : ADDRESS   = the odd target address (bit 0 preserved)
|
| Six stages, one per transfer kind.  Per-stage protocol:
|   A4 = expected fault PC (the transfer instruction)
|   A3 = expected odd target
|   A5 = continuation address (handler patches frame PC, RTEs there)
|   D6 = stage counter, must be 6 at the end.
|
| Documented µarch semantics locked in by this test:
|   - JSR/BSR: the return-address push (phase-0 STORE of the crack) retires
|     BEFORE the branch phase faults, so at the handler A7 is already
|     pre-JSR/BSR minus 4 (matches real-040 push-then-prefetch order).
|   - RTS: the faulting RTS does NOT update arch state — the odd return
|     address is still on the stack and A7 is unchanged.
|   - DBcc: the counter decrement is suppressed on the faulting path
|     (fault µop never commits its Dn writeback).
|
| PASS: all six stages trap with the exact frame above, D6 == 6.
| FAIL: any frame-field mismatch, or a stage falls through untrapped.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_handler3, 0x0000000C  | vector 3 (address error)
    moveq   #0, %d6

    | ── Stage 1: JMP (An) to odd address ─────────────────────────────
    lea     _s1_target, %a0
    addq.l  #1, %a0
    move.l  %a0, %a3                | expected odd target
    lea     _s1_jmp, %a4            | expected fault PC
    lea     _s1_cont, %a5
_s1_jmp:
    jmp     (%a0)
_s1_target:
    nop                             | never executed
_s1_cont:

    | ── Stage 2: JSR (An) to odd address ─────────────────────────────
    lea     _s2_target, %a0
    addq.l  #1, %a0
    move.l  %a0, %a3
    lea     _s2_jsr, %a4
    lea     _s2_cont, %a5
    move.l  %a7, %a6                | pre-JSR SP snapshot
_s2_jsr:
    jsr     (%a0)
_s2_target:
    nop                             | never executed
_s2_cont:
    | Return-address push retired before the branch faulted: A7 must be
    | pre-JSR minus 4.  Discard the stale return address.
    move.l  %a7, %d0
    addq.l  #4, %d0
    cmp.l   %a6, %d0
    bne     _fail
    addq.l  #4, %a7

    | ── Stage 3: BRA.s with an odd displacement ──────────────────────
    lea     _s3_bra, %a4
    lea     _s3_bra+5, %a3          | target = pc + 2 + disp(3) = odd
    lea     _s3_cont, %a5
_s3_bra:
    .short  0x6003                  | bra.s .+5 (odd target)
    nop                             | never executed
_s3_cont:

    | ── Stage 4: RTS popping an odd return address ───────────────────
    lea     _s4_odd, %a3
    addq.l  #1, %a3                 | odd "return address"
    lea     _s4_rts, %a4
    lea     _s4_cont, %a5
    move.l  %a3, -(%a7)
_s4_rts:
    rts
_s4_odd:
    nop                             | never executed
_s4_cont:
    | The faulting RTS must NOT have updated arch state: the odd return
    | address is still on the stack.  Assert + clean it up.
    move.l  (%a7)+, %d0
    cmp.l   %a3, %d0
    bne     _fail

    | ── Stage 5: DBF with an odd displacement ────────────────────────
    moveq   #1, %d0
    lea     _s5_dbf, %a4
    lea     _s5_dbf+5, %a3          | target = (pc+2) + disp(3) = odd
    lea     _s5_cont, %a5
_s5_dbf:
    .short  0x51C8, 0x0003          | dbf %d0, .+3 (odd target)
    nop                             | never executed
_s5_cont:
    | Counter decrement suppressed on the faulting path.
    cmp.w   #1, %d0
    bne     _fail

    | ── Stage 6: BSR.s with an odd displacement ──────────────────────
    lea     _s6_bsr, %a4
    lea     _s6_bsr+5, %a3          | target = pc + 2 + disp(3) = odd
    lea     _s6_cont, %a5
    move.l  %a7, %a6                | pre-BSR SP snapshot
_s6_bsr:
    .short  0x6103                  | bsr.s .+5 (odd target)
    nop                             | never executed
_s6_cont:
    | Like JSR: the return-address push retired before the branch phase
    | faulted.  The stale return address is _s6_bsr+2.
    move.l  %a7, %d0
    addq.l  #4, %d0
    cmp.l   %a6, %d0
    bne     _fail
    move.l  (%a7)+, %d0
    lea     _s6_bsr+2, %a1
    cmp.l   %a1, %d0
    bne     _fail

    | ── Final: all six stages must have trapped ──────────────────────
    cmp.l   #6, %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt

| ── Vector-3 handler: assert the format-$2 frame, resume at A5 ───────
_handler3:
    | format/vector word: format $2, vector offset 0x00C
    move.w  6(%a7), %d7
    cmp.w   #0x200C, %d7
    bne     _fail
    | frame PC = the transfer instruction (A4)
    move.l  2(%a7), %d7
    cmp.l   %a4, %d7
    bne     _fail
    | instruction-address field = the odd target (A3)
    move.l  8(%a7), %d7
    cmp.l   %a3, %d7
    bne     _fail
    addq.l  #1, %d6
    | Patch the frame PC to the continuation and return.
    move.l  %a5, 2(%a7)
    rte
