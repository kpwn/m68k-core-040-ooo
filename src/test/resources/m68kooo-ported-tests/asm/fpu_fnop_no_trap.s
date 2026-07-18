| fpu_fnop_no_trap.s -- FNOP (FBF.W #0) must execute as a genuine no-op,
| not trap.
|
| Code review (this session) found decode_1111.vh's FBcc.W row
| explicitly EXCLUDED cond==0 (FBF, "false") via `op_f3[5:0] != 6'd0`,
| despite that row's own comment already claiming "the simplest
| landing emits BR_FBCC and lets the ALU evaluate cond=0 -> never
| taken naturally".  Because cond==0 was excluded, FNOP -- which is
| architecturally just FBF.W with a (irrelevant, since never taken)
| branch displacement, commonly encoded `F280 0000` -- fell all the
| way through the casez undecoded and hit decode.v's uop_type==UOP_NOP
| decode-time fallback, which turns any un-decoded opword whose top
| nibble is 1111 into a vec-11 F-line trap.  So FNOP was trapping
| instead of executing as a no-op.
|
| Note on the fix shape: emitting a bespoke UOP_NOP for this row would
| NOT have fixed this -- decode.v's fallback
| (`if (uop_type == UOP_NOP && uop_phase_f3 == 0)`) fires unconditionally
| whenever pd_valid_f3, with no way to distinguish "explicitly decoded
| as NOP" from "nothing matched"; it would still reclassify the row as
| a vec-11 trap immediately afterward in the same always block.  (The
| real 0x4E71 NOP survives this same fallback only because a LATER V2
| sysop override re-clobbers it, and F-line/FPU decode is not on the V2
| path.)  The actual fix routes cond==0 through the SAME BR_FBCC row
| used for every other FBcc condition: flags_rd={1'b0,op_f3[3:0]}=0
| selects alu.v's fpcc_true case 4'h0 = 1'b0 (always false), so the
| branch unit evaluates it as never-taken with zero side effects (no
| register writes, flags_wr=0) -- a genuine architectural no-op.  This
| matches Musashi's fbcc16()/fbcc32() (tb/models/musashi/m68kfpu.c),
| which simply skip m68ki_branch_16()/32() whenever TEST_CONDITION()
| is false -- condition 0 included, no special-casing.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0F01 -- vec-11 F-line trap (regression: FNOP traps again)
|   0xDEAD0FB0 -- FNOP clobbered a data register (side effect)
|   0xDEAD0FB1 -- FNOP clobbered CCR/SR (side effect)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 (F-line)

    | Establish known, distinguishable register + CCR state.
    move.l  #0x12345678, %d0
    move.l  #0x87654321, %d1
    moveq   #0, %d5
    cmp.l   %d5, %d5                   | Z=1,N=0,V=0,C=0
    move.w  %sr, %d3                   | snapshot SR (incl CCR)

    | FNOP.  Standard idiom / encoding: FBF.W with a zero branch
    | displacement.  opword 0xF280 = FBcc.W, cond field (opword[5:0])
    | = 0 (F = never taken, the "always false" IEEE condition).
    | ext1 = 0x0000 (disp16 = 0; irrelevant since cond=F never
    | branches -- PRM defines FNOP as exactly this encoding).
_fnop_under_test:
    .short  0xF280, 0x0000              | FNOP

    | Verify: no side effects, and control genuinely fell through
    | (not trapped, not branched away).
    cmp.l   #0x12345678, %d0
    bne     _fail_regs
    cmp.l   #0x87654321, %d1
    bne     _fail_regs
    move.w  %sr, %d4
    cmp.w   %d3, %d4
    bne     _fail_ccr

    | Second check: FBF with a NONZERO displacement must also fall
    | through (never taken) with no trap.  The fix routes cond==0
    | through the same BR_FBCC row as every other FBcc condition
    | regardless of the encoded displacement, so behaviour must not
    | depend on the disp16 value.
_fbf_nonzero_disp:
    .short  0xF280, 0x0010              | FBF.W +16 (never taken)

    cmp.l   #0x12345678, %d0
    bne     _fail_regs
    cmp.l   #0x87654321, %d1
    bne     _fail_regs
    move.w  %sr, %d4
    cmp.w   %d3, %d4
    bne     _fail_ccr

    | PASS
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_regs:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FB0, %d2
    move.l  %d2, (%a1)
_halt_regs:
    bra     _halt_regs

_fail_ccr:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0FB1, %d2
    move.l  %d2, (%a1)
_halt_ccr:
    bra     _halt_ccr

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
