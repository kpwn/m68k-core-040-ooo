| fpu_fbcc_condition_matrix.s — FBcc across the FP condition table, including the
| UNORDERED (NaN) cases that are the whole point of having FP conditions.
|
| fpu_fbcc_branch pins one condition (FBEQ after FCMP FP0,FP0).  This walks the ordered
| conditions in both directions and then the unordered ones against a real NaN, which is
| where the FP predicates stop resembling the integer ones: with NaN set, OGT/OGE/OLT/OLE
| are ALL false and UGT/UGE/ULT/ULE are ALL true, regardless of N and Z.
|
| Encodings (all emitted raw so the test does not depend on assembler FP support):
|   FMOVE.L Dn,FPm   = F200|<ea=Dn>  , ext 0x4000 | (fmt Long=000)<<10 | FPm<<7 | op 0x00
|   FMOVE.S (An),FPm = F200|<ea=(An)>, ext 0x4000 | (fmt Sgl =001)<<10 | FPm<<7 | op 0x00
|   FCMP.X  FPm,FPn  = F200          , ext (FPm<<10) | (FPn<<7) | 0x38   -> FPCC = FPn-FPm
|   FBcc.W           = F280 | cc, followed by a disp16 relative to the DISPLACEMENT WORD.
| Each displacement is written `_label - .`, which the assembler evaluates at the .short
| itself — i.e. exactly the PC+2 reference point an FBcc.W uses — so no hand arithmetic.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels: 0xDEAD0C<nn>, one per check (see each site).  0xDEAD0CFF = F-line trap.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C

    | ===== ordered, FP0 = 1.0 < FP1 = 2.0  ->  N=1, Z=0, NAN=0 =====
    moveq   #1, %d0
    moveq   #2, %d1
    .short  0xF200, 0x4000             | FMOVE.L D0,FP0   (1.0)
    .short  0xF201, 0x4080             | FMOVE.L D1,FP1   (2.0)
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0  -> FP0-FP1 = -1.0

    .short  0xF281                     | FBEQ  — must NOT be taken
    .short  _f01 - .
    .short  0xF28E                     | FBNE  — must be taken
    .short  _c02 - .
    bra     _f02
_c02:
    .short  0xF282                     | FBOGT — must NOT be taken
    .short  _f03 - .
    .short  0xF284                     | FBOLT — must be taken
    .short  _c04 - .
    bra     _f04
_c04:
    .short  0xF283                     | FBOGE — must NOT be taken
    .short  _f05 - .
    .short  0xF285                     | FBOLE — must be taken
    .short  _c06 - .
    bra     _f06
_c06:
    .short  0xF287                     | FBOR  — ordered, must be taken
    .short  _c07 - .
    bra     _f07
_c07:
    .short  0xF288                     | FBUN  — must NOT be taken
    .short  _f08 - .

    | ===== equal, FP0 = FP1 = 2.0  ->  Z=1, N=0, NAN=0 =====
    .short  0xF201, 0x4000             | FMOVE.L D1,FP0   (2.0)
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0  -> 0.0

    .short  0xF28E                     | FBNE  — must NOT be taken
    .short  _f09 - .
    .short  0xF281                     | FBEQ  — must be taken
    .short  _c10 - .
    bra     _f10
_c10:
    .short  0xF283                     | FBOGE — Z -> must be taken
    .short  _c11 - .
    bra     _f11
_c11:
    .short  0xF285                     | FBOLE — Z -> must be taken
    .short  _c12 - .
    bra     _f12
_c12:
    .short  0xF282                     | FBOGT — must NOT be taken
    .short  _f13 - .

    | ===== UNORDERED: FP0 = NaN  ->  NAN=1 =====
    lea     _nan_s, %a0
    .short  0xF210, 0x4400             | FMOVE.S (A0),FP0  (0x7FC00000 = qNaN)
    .short  0xF200, 0x0438             | FCMP.X  FP1,FP0   -> unordered

    .short  0xF288                     | FBUN  — must be taken
    .short  _c14 - .
    bra     _f14
_c14:
    .short  0xF287                     | FBOR  — must NOT be taken
    .short  _f15 - .
    .short  0xF282                     | FBOGT — ordered cond, NaN -> NOT taken
    .short  _f16 - .
    .short  0xF284                     | FBOLT — ordered cond, NaN -> NOT taken
    .short  _f17 - .
    .short  0xF283                     | FBOGE — ordered cond, NaN -> NOT taken
    .short  _f18 - .
    .short  0xF285                     | FBOLE — ordered cond, NaN -> NOT taken
    .short  _f19 - .
    .short  0xF28A                     | FBUGT — unordered cond, NaN -> taken
    .short  _c20 - .
    bra     _f20
_c20:
    .short  0xF28C                     | FBULT — unordered cond, NaN -> taken
    .short  _c21 - .
    bra     _f21
_c21:
    .short  0xF289                     | FBUEQ — NaN|Z -> taken
    .short  _c22 - .
    bra     _f22
_c22:
    .short  0xF281                     | FBEQ  — Z alone, NaN does not set it -> NOT taken
    .short  _f23 - .

    | ===== the SIGNALING aliases 0x10..0x1F, still with FP0 = NaN =====
    | 0x10..0x1F are the signaling spellings of 0x00..0x0F and are IDENTICAL in truth
    | value (they differ only in raising BSUN on an unordered operand), which is what lets
    | the branch carry cc[3:0] alone.  SF in particular is the encoding that reaches the
    | branch EU with cond==0 — FBF proper decodes as a NOP — and cond==0 means "always
    | taken" in the INTEGER table, so this is the case that catches a branch classifier
    | reading the wrong table.
    .short  0xF290                     | FBSF  (0x10) — never taken
    .short  _f25 - .
    .short  0xF29F                     | FBST  (0x1F) — always taken
    .short  _c26 - .
    bra     _f26
_c26:
    .short  0xF29E                     | FBSNE (0x1E) — !Z, Z=0 under NaN -> taken
    .short  _c27 - .
    bra     _f27
_c27:
    .short  0xF291                     | FBSEQ (0x11) — Z=0 under NaN -> NOT taken
    .short  _f28 - .

    | ===== FBcc.L (type 011): same condition, 32-bit displacement =====
    | FBUN.L = F2C8, disp32 relative to the first displacement word.
    .short  0xF2C8
    .long   _c24 - .
    bra     _f24
_c24:

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_f01: moveq #0x01, %d2
    bra _report
_f02: moveq #0x02, %d2
    bra _report
_f03: moveq #0x03, %d2
    bra _report
_f04: moveq #0x04, %d2
    bra _report
_f05: moveq #0x05, %d2
    bra _report
_f06: moveq #0x06, %d2
    bra _report
_f07: moveq #0x07, %d2
    bra _report
_f08: moveq #0x08, %d2
    bra _report
_f09: moveq #0x09, %d2
    bra _report
_f10: moveq #0x10, %d2
    bra _report
_f11: moveq #0x11, %d2
    bra _report
_f12: moveq #0x12, %d2
    bra _report
_f13: moveq #0x13, %d2
    bra _report
_f14: moveq #0x14, %d2
    bra _report
_f15: moveq #0x15, %d2
    bra _report
_f16: moveq #0x16, %d2
    bra _report
_f17: moveq #0x17, %d2
    bra _report
_f18: moveq #0x18, %d2
    bra _report
_f19: moveq #0x19, %d2
    bra _report
_f20: moveq #0x20, %d2
    bra _report
_f21: moveq #0x21, %d2
    bra _report
_f22: moveq #0x22, %d2
    bra _report
_f23: moveq #0x23, %d2
    bra _report
_f24: moveq #0x24, %d2
    bra _report
_f25: moveq #0x25, %d2
    bra _report
_f26: moveq #0x26, %d2
    bra _report
_f27: moveq #0x27, %d2
    bra _report
_f28: moveq #0x28, %d2
    bra _report
_fline: moveq #0x7F, %d2

_report:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD0C00, %d3
    or.l    %d2, %d3
    move.l  %d3, (%a1)
_fhalt:
    bra     _fhalt

    .align  2
_nan_s:
    .long   0x7FC00000
