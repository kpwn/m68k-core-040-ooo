| movea_w_pc_brief_scale.s — verify (d8, PC, Dn.W*scale) brief EA.
|
| HW investigation 2026-05-21: Q700 ROM uses MOVEA.W (d8, PC, D0.W*2), A0
| at 0x40809BCE as part of the VBL IRQ handler dispatch.  MAME computes
| the EA with scale=2 and lands at A0 = MEM[0x192] = 0x4080B140 (valid
| ROM).  HW investigation suggests our impl may not honor the scale
| field correctly for brief-format PC-indexed addressing.
|
| Test:
|   1. Pre-populate a sentinel at the address that scale=2 EA would read.
|   2. Pre-populate a DIFFERENT sentinel at the scale=1 EA.
|   3. Execute MOVEA.W (d8, PC, D0.W*scale), A0 with scale=2.
|   4. Check A0 holds the scale=2 sentinel.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    move.w  #0x2700, %sr

    | Set D0 to a known value.  D0.W = 0x0010.  D0.W * 2 = 0x0020.
    move.l  #0x00000010, %d0

    | The MOVEA.W instruction we want to test:
    |   307b XXXX    MOVEA.W (d8, PC, D0.W*scale), A0
    | where ext word XXXX encodes:
    |   D/A=0 (D), Xn=000 (D0), W/L=0 (W), scale=01 (=2), brief=0, d8=XX
    | So XXXX = 0000_0010_0_XX (with scale field at bits 10-9 = 01)
    |        = 0x02XX
    | We want d8 = 4 so EA = (PC_base) + 4 + (D0.W * scale).
    | With scale=2 and D0.W=0x10: EA = PC_base + 4 + 0x20 = PC_base + 0x24.
    | With scale=1 and D0.W=0x10: EA = PC_base + 4 + 0x10 = PC_base + 0x14.

    | The PC base for (d8, PC, Xn) brief per spec = pd_pc + 2 = addr of ext word.
    | So if the MOVEA.W opcode is at PC=X:
    |   X+0,1 = opcode bytes (0x30 0x7B)
    |   X+2,3 = ext word bytes
    |   X+4 onwards = next inst
    |
    | PC_base = X + 2.
    | scale=2 EA = (X+2) + 4 + 0x20 = X + 0x26
    | scale=1 EA = (X+2) + 4 + 0x10 = X + 0x16
    |
    | Place sentinel at offsets X+0x16 (scale=1) and X+0x26 (scale=2).

_movea:
    .word 0x307b      | MOVEA.W (d8, PC, D0.W*scale), A0
    .word 0x0204      | ext: D/A=0, reg=0, W=0, scale=01 (=2), brief=0, d8=0x04

    | At this point A0 should = sign_ext(MEM_W[scale=2 EA]) = sign_ext(SENTINEL_S2).

    | Check A0:
    cmpa.l  #0x12345678, %a0       | Match scale=2 sentinel (sign-ext from word)
    bne     _check_scale1

    | scale=2 worked.
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_check_scale1:
    cmpa.l  #0x76543210, %a0       | scale=1 sentinel (= we used wrong PC base or no scale)
    bne     _unknown
    move.l  #0xDEAD0001, 0xFFFF0000  | sentinel == scale=1 → bug
    bra     .

_unknown:
    | A0 has some other value (e.g., from PC+4 base or no scale and PC+4 base).
    | Write A0 itself for diagnostic.
    move.l  %a0, 0xFFFF0000
    bra     .

    | Pad until X+0x16 (= the scale=1 EA target).
    | _movea is at offset (well, we need to compute).
    | The label _movea is after the move.w #imm,%sr (= 4 bytes) and
    | move.l #imm,%d0 (= 6 bytes) and lea (= 6 bytes).  16 bytes of setup.
    | After _movea label, MOVEA.W = 4 bytes (opcode 2 + ext 2).
    | Then the cmpa.l + bne + move.l + bra etc.
    | Easier: use .org to place sentinels at specific addresses.

    .org 64
    | offset 64 from _start.  Hmm but _movea position depends on prior insns.
    | Let me just use absolute addresses via .org.

    | Actually — putting sentinels at fixed _start-relative offsets requires
    | knowing _movea's address.  Let me use a different approach:
    | Instead of inlining the test code, use a SUBROUTINE that does the MOVEA
    | with sentinels precisely placed via .org.

    | Actually scrap this approach.  Just emit DIAG bytes around the MOVEA
    | location and let A0 read them; we'll check the result against the bytes
    | we placed.
