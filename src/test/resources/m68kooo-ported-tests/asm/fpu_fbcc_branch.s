| fpu_fbcc_branch.s — FBcc branch on a known FCC condition.
|
| Goal: drive an FCMP that establishes a known FPCC state, then take an
| FBcc that branches on that state.  Pick FBEQ on equal floats: after
| FCMP FP0,FP0 (compare a register against itself) Z is set, all others
| clear, so FBEQ taken.
|
| FCMP.X FPm,FPn: opword=0xF200, ext = (FPm<<10)|(FPn<<7) | 0x38
|   FCMP.X FP0,FP0: ext = 0x0038
|
| FBcc.W word: opword = 0xF280 | cond[5:0]
|   FBEQ = cond 0x01.  Opword = 0xF281.  Followed by 16-bit signed
|   displacement to PC + 2.
|
| EXPECTED OUTCOME: FCMP and FBcc are NOT in the register-register
| ALU set (cited cases 0x00 / 0x18 / 0x1a / 0x20 / 0x22 / 0x23 / 0x28).
| FCMP (0x38) is not on that list.  FBcc.W (opword 0xF281) does not
| match the F2/F3 prefix at all and traps via vec-11 F-line.
|
| PASS sentinel: 0xC0FFEE00 if FBEQ took (control reaches _pass).
| FAIL sentinels:
|   0xDEAD0F01 — F-line trap (FCMP or FBcc undecoded)
|   0xDEAD0F09 — FBEQ NOT taken (fell through to _fail)
|
| OBSERVED on main: assembles; runtime expected DEAD0F01.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    | FCMP.X FP0,FP0 — Z set
    .short  0xF200, 0x0038

    | FBEQ.W to _pass.  Use the assembler's branch encoding via .short.
    | Compute displacement at link time using a label trick: emit raw
    | opword + a placeholder displacement, then patch via subq logic.
    | Actually simpler: emit FBEQ as raw and use a small +6 branch over
    | a fixed FAIL block.  Layout:
    |   +0: F281 (FBEQ.W)
    |   +2: 0006  (disp = 6, target = PC+2+6 = +8 from FBEQ)
    |   +4: bra _fail (falls through if FBEQ not taken)
    |   +8: target -> _pass
    .short  0xF281, 0x0006             | FBEQ.W +6
    bra     _fail

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F09, %d2
    move.l  %d2, (%a1)
_halt_fail:
    bra     _halt_fail

_fline:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0F01, %d2
    move.l  %d2, (%a1)
_halt_fline:
    bra     _halt_fline
