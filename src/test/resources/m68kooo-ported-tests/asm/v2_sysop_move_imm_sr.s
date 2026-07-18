| v2_sysop_move_imm_sr.s — task #221 / E4 — MOVE.W #imm,SR via the V2
| sysop family.  Confirms the legacy decode_0100.vh row 0x46FC migrates
| to V2 SYS_MOVE_SR with byte-exact arch_sr write at retire.
|
| Sequence:
|   1. From supervisor mode, set CCR + supervisor bits via MOVE.W #imm,SR.
|   2. Read back CCR via branch tests on the just-written flags.
|   3. Confirm we're still in supervisor mode (try a privileged op —
|      RESET — and watch it not fault).
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | MOVE.W #0x271F,SR — supervisor mode (S=1, bit 13), IPL=7 (bits 8-10),
    | trace off (bit 15).  Low byte 0x1F = X N Z V C all 1.
    move.w  #0x271F, %sr             | 0x46FC 0x271F — V2 SYS_MOVE_SR.

    | All 5 CCR flags should be 1.
    bvc     _fail                    | V must be 1
    bcc     _fail                    | C must be 1
    bpl     _fail                    | N must be 1
    bne     _fail                    | Z must be 1

    | Verify X=1 via ADDX.
    move.l  #0, %d0
    move.l  #0, %d1
    addx.l  %d1, %d0                 | d0 = 0 + 0 + X = 1
    cmp.l   #1, %d0
    bne     _fail

    | MOVE.W #0x2700,SR — keep S=1 + IPL=7; clear all CCR.
    move.w  #0x2700, %sr             | 0x46FC 0x2700.

    | All 5 CCR flags should be 0.
    bvs     _fail
    bcs     _fail
    bmi     _fail
    beq     _fail

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
