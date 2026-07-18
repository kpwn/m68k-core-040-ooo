| bsr_rts_odd_sp.s — BSR.W + RTS at ODD A7 (Q700 boot Bug B post-IMMU)
|
| Repro target: live FPGA Sad Mac after-fetch-MMU.  Wild PC = 0x6B1A_000C
| where 0x6B1A is the BSR.W return-addr from low-RAM routine at 0x6B0A.
| ROM sequence:
|   0x6B0C  MOVE.B D0,-(SP)         ← A7 -= 1, becomes ODD
|   0x6B14  MOVE.W SR,-(SP)         ← A7 -= 2, still ODD
|   0x6B16  BSR.W +0x6B2 → 0x71CA   ← A7 -= 4, push 4-byte ret-PC at ODD A7
|   0x6B1A  BTST #12,D5             ← target of eventual RTS
|
| HW JTAG observation: RTS pops 0x6B1A_000C instead of 0x0000_6B1A.
| The 0x6B1A high half is correct, 0x000C low half is from bytes
| ADJACENT to the return-PC slot — strongly suggests misaligned-long
| pop returning shifted bytes.
|
| Existing tb-lsu directed tests for general misaligned MOVE.L pass —
| so the bug must be specific to the BSR/RTS push-pop pipeline path,
| or at the corner where pre-pushed words on the stack INTERFERE with
| the BSR push/RTS pop's misaligned-long handling.
|
| Test plan: assemble exactly MOVE.B,-(SP); MOVE.W SR,-(SP); BSR.W;
| sub-with-misaligned-pushes; RTS, with a SENTINEL value adjacent so
| any byte-misshift in pop is detectable as wrong return-PC.
|
| Iter 1 — odd A7 from MOVE.B (the 0x6B0C pattern)
| Iter 2 — even A7 control (must pass — proves baseline)

    .text
    .org 0

| --- Test sentinel addresses ---
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | Initial A7 = 1 MB into RAM (well above test code at 0x40800000-).
    move.l  #0x00100000, %a7

    | --- Iteration 1: ODD A7 path (matches Bug B HW pattern) ---
    | Plant SENTINEL bytes adjacent to where BSR-push will land.
    | NOTE: 68k PRM §3.2 byte-rule for A7 — `MOVE.B Dn,-(A7)` decrements
    | A7 by 2 (not 1), keeping A7 word-aligned.  Matches Musashi
    | EA_A7_PD_8 = (REG_A[7] -= 2).  To intentionally create an odd A7
    | we use SUBA.L #1,A7 (alterable).
    | After SUBA.L #1,SP:    A7 = 0x000FFFFF (odd).
    | After MOVE.W SR,-(SP): A7 = 0x000FFFFD (odd).
    | After BSR.W:           A7 = 0x000FFFF9 (odd).
    | BSR pushes 4 bytes at 0x000FFFF9..0x000FFFFC.  Sentinels below
    | (0x000FFFF4..0x000FFFF8) reveal misaligned reads.
    move.l  #0xCAFE0011, 0x000FFFF4
    move.l  #0xCAFE0022, 0x000FFFF8

    suba.l  #1, %a7                     | A7 = 0x000FFFFF (odd)
    move.w  %sr, -(%a7)                 | A7 = 0x000FFFFD (odd)
    bsr.w   _sub_check_pushed_pc        | A7 = 0x000FFFF9 (odd) on push
                                        | sub will check stack contents
_after_bsr1:
    | If we land HERE, RTS popped correctly (PC = inst after bsr).  Restore.
    addq.w  #2, %a7                     | drop SR (A7 = 0x000FFFFF)
    adda.l  #1, %a7                     | undo SUBA.L #1 (A7 = 0x00100000)
    cmp.l   #0x00100000, %a7
    bne     _fail_iter1_sp_wrong        | SP not back to original = corruption

    | --- Iteration 2: EVEN A7 control (baseline — must pass) ---
    move.l  #0x00100000, %a7
    move.w  %sr, -(%a7)                 | A7 = 0x000FFFFE (even)
    bsr.w   _sub_simple                 | A7 = 0x000FFFFA (even)
    addq.w  #2, %a7
    cmp.l   #0x00100000, %a7
    bne     _fail_iter2_sp_wrong

    | --- Iteration 3: ODD A7 from raw odd address (no MOVE.B) ---
    move.l  #0x000FFFFF, %a7            | A7 set ODD directly
    move.w  %sr, -(%a7)                 | A7 = 0x000FFFFD (odd)
    bsr.w   _sub_simple                 | A7 = 0x000FFFF9 (odd)
    addq.w  #2, %a7
    cmp.l   #0x000FFFFF, %a7
    bne     _fail_iter3_sp_wrong

    | --- All passed — PASS sentinel ---
    move.l  #0x00100000, %a7
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

| Subroutine that VERIFIES the BSR-pushed return-PC by reading it
| from the stack at SP+0 (4-byte read at odd SP — misaligned).
| Pre: A7 odd, BSR just pushed 4-byte return-PC at A7..A7+3.
_sub_check_pushed_pc:
    | Expected return-PC = address of inst after BSR in caller (label
    | `_after_bsr1` — robust against caller-side reshuffling).
    | Read what BSR actually pushed:
    move.l  (%a7), %d7                   | d7 = stack[A7..A7+3]
    | Dump D7 to a debug-visible RAM location BEFORE comparing, so
    | a waveform / framebuffer-poke / dump-mem sees the exact bytes.
    move.l  #0xCAFEBA00, 0x002FFF00       | marker before
    move.l  %d7, 0x002FFF04               | actual pushed value
    move.l  #0xCAFEBAFF, 0x002FFF08       | marker after
    cmp.l   #_after_bsr1, %d7
    bne     _fail_iter1_pushed_wrong     | BSR push wrote wrong bytes

    | Push more longs at odd A7 (mimic 0x71CA subroutine pattern).
    move.l  %a0, -(%a7)
    move.l  %d0, -(%a7)
    move.w  %sr, -(%a7)
    moveq   #7, %d0
    and.b   (%a7), %d0
    move.w  (%a7)+, %d0
    move.l  (%a7)+, %d0
    move.l  (%a7)+, %a0

    | Re-verify return-PC slot still has correct bytes BEFORE RTS pops.
    move.l  (%a7), %d7
    cmp.l   #_after_bsr1, %d7
    bne     _fail_iter1_pre_rts_wrong   | something corrupted return-PC

    rts                                  | misaligned long pop

| Trivial subroutine for control / iter-3
_sub_simple:
    nop
    nop
    rts

| --- Failure handlers ---
| Each writes a unique magic to FAIL_ADDR so the harness can tell
| which test branch failed.
_fail_iter1_pushed_wrong:
    | Write the ACTUAL bsr-pushed value (in D7) to FAIL_ADDR so the
    | sim harness reports it.  Expected = 0x40800028.
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b

_fail_iter1_pre_rts_wrong:
    | Dump the wrong return-PC slot value (in D7) so we can see exactly
    | what bytes the misaligned pushes/pops corrupted to.
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b

_fail_iter1_sp_wrong:
    lea     FAIL_ADDR, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
1:  bra     1b

_fail_iter2_sp_wrong:
    lea     FAIL_ADDR, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
1:  bra     1b

_fail_iter3_sp_wrong:
    lea     FAIL_ADDR, %a0
    move.l  #0xDEAD0005, %d0
    move.l  %d0, (%a0)
1:  bra     1b
