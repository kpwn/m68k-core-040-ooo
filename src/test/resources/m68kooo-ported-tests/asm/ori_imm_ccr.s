| ori_imm_ccr.s — task #236 / F4 — ORI #imm.B,CCR via V2 sysop family.
|
| Verifies SYS_ORI_CCR retire path: arch CCR ← arch CCR | imm[7:0].
| User-mode legal.  Single-µop UOP_SYS / SYS_ORI_CCR.
|
| Pipeline note: SYS_*_CCR retire forces flush+redirect (commit.v F4
| block) so a directly-following Bcc observes the updated CCR.
|
| CCR layout (uop_pkg.v): bits [4:0] = X N Z V C.
|   X = bit 4 (mask 0x10), N = bit 3 (0x08), Z = bit 2 (0x04),
|   V = bit 1 (0x02),      C = bit 0 (0x01).
|
| Sequence (each phase is independent so the retire-flush refreshes
| the rename-snapshot before each Bcc reads CCR):
|   1. MOVE.W #0x0000,CCR; ORI #0x01,CCR; BCS _pass1   — set C only.
|   2. MOVE.W #0x0000,CCR; ORI #0x02,CCR; BVS _pass2   — set V only.
|   3. MOVE.W #0x0000,CCR; ORI #0x04,CCR; BEQ _pass3   — set Z only.
|   4. MOVE.W #0x0000,CCR; ORI #0x08,CCR; BMI _pass4   — set N only.
|   5. MOVE.W #0x0000,CCR; ORI #0x10,CCR; verify X via ADDX.
|   6. MOVE.W #0x0000,CCR; ORI #0x05,CCR; BCS  _pass6  — set Z+C; check C=1.
|                                          BEQ  _pass6b — and Z=1.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Phase 1: ORI #0x01 — set C.
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0001           | ORI #0x01,CCR
    bcc     _fail                    | C=1 → BCC must NOT branch.

    | Phase 2: ORI #0x02 — set V.
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0002           | ORI #0x02,CCR
    bvc     _fail                    | V=1 → BVC must NOT.

    | Phase 3: ORI #0x04 — set Z.
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0004           | ORI #0x04,CCR
    bne     _fail                    | Z=1 → BNE must NOT.

    | Phase 4: ORI #0x08 — set N.
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0008           | ORI #0x08,CCR
    bpl     _fail                    | N=1 → BPL must NOT.

    | Phase 5: ORI #0x10 — set X.  Verify via ADDX (d0=0,d1=0,X=1 → d1=1).
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0010           | ORI #0x10,CCR
    moveq   #0, %d0
    moveq   #0, %d1
    addx.l  %d0, %d1
    cmp.l   #1, %d1
    bne     _fail

    | Phase 6: ORI #0x05 — Z + C combined.
    move.w  #0x0000, %ccr
    .word   0x003C, 0x0005           | ORI #0x05,CCR
    bcc     _fail                    | C=1.
    bne     _fail                    | Z=1.

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
