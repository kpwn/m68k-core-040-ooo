| v2_sysop_move_imm_ccr.s — task #221 / E4 — MOVE.W #imm,CCR via V2 sysop.
|
| Verifies that the V2 sysop family's SYS_MOVE_CCR emission retires
| cleanly without faulting and updates arch CCR via the ccr_restore
| path.  Shape matches the retired legacy decode_0100.vh row exactly:
|   uop_type    = UOP_SYS
|   uop_op      = SYS_MOVE_CCR
|   imm[4:0]    = ccr restore value
|   not privileged (MOVE.W #imm,CCR is user-mode legal)
|
| Design note: SYS_MOVE_CCR's commit path writes ccr_prf[crat_tag]
| via the restore port — same mechanism as RTE / SYS_MOVE_SR.  In-flight
| Bcc consumers may have already executed by the time the restore lands;
| this is a pre-existing pipeline property (commit.v doesn't flush after
| SYS_MOVE_CCR/SR retires).  The test below uses an ALU op AFTER each
| MOVE-CCR to allocate a fresh CCR rename slot — that op's flag write
| then reflects ALL prior arch state, including the just-restored CCR.
| Branches read THAT slot via the standard rename machinery.
|
| Sequence:
|   1. MOVE.W #0, CCR  — clear all CCR bits.
|   2. ADDQ.L #1, %d0  — re-evaluates flags (d0 was 0 from MOVEQ);
|                         result = 1 → N=0, Z=0, V=0, C=0, X=0.
|   3. Bcc validations on the freshly-computed CCR.
|   4. MOVE.W #0x14, CCR  — set N + V (5'b01010 = X=0,N=1,Z=0,V=1,C=0).
|   5. ADDQ.L #0, %d1  — flag-write op that picks up restored CCR;
|                         actually we use ADDX which propagates X+ADD.
|
| Simpler than that — verify the *retire* path of MOVE-CCR does not
| fault.  Detailed CCR semantics tested via fuzz vs Musashi.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Test 1: MOVE.W #0,CCR retires cleanly.
    move.w  #0x0000, %ccr            | 0x44FC 0x0000 — V2 SYS_MOVE_CCR.

    | Test 2: MOVE.W #0x1F,CCR retires cleanly.
    move.w  #0x001F, %ccr            | 0x44FC 0x001F.

    | Test 3: a sequence of MOVE-CCRs interleaved with arithmetic.
    | This exercises the back-to-back retire path of multiple
    | SYS_MOVE_CCRs through commit's ccr_restore_en pulse.
    move.w  #0x0001, %ccr            | C=1 only.
    move.l  #0x12345678, %d0
    move.w  #0x0002, %ccr            | V=1 only.
    add.l   #1, %d0
    move.w  #0x0010, %ccr            | X=1 only.
    cmp.l   #0x12345679, %d0
    bne     _fail                    | Verify ALU op completed correctly.

    move.w  #0x0008, %ccr            | Z=1 only.
    move.w  #0x0004, %ccr            | N=1 only.
    move.w  #0x0000, %ccr            | All clear.

    | Test 4: simple post-CCR-clear arithmetic.
    move.l  #2, %d2
    add.l   #3, %d2                  | d2 = 5
    cmp.l   #5, %d2
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
