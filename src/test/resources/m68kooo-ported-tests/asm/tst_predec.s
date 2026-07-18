| tst_predec.s — TST with predecrement -(An) addressing must decode + run.
|
| Clean-slate Q700 Sad Mac root cause: the decoder illegal-traps
| TST.W -(A0) (opword 0x4A60) → vec-4 → ROM illegal handler → Sad Mac.
| The Q700 ROM uses TST.W -(A0) in a list-compaction loop at ROM
| 0x4083c1a0; the same routine's TST.W (A0)+ (0x4A58) decodes fine, so
| the gap is specifically the -(An) predecrement EA mode for TST.
|
| TST.W/L -(An): A0 -= size; read; set N/Z from the value; clear V/C.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0004  vec-4 illegal-instruction trap — THE BUG
|   0xDEAD00A0  TST.W -(A0) left A0 wrong (not decremented by 2)
|   0xDEAD0F1A  TST.W -(A0) flags wrong (negative word → N must be set)
|   0xDEAD00A1  TST.L -(A0) left A0 wrong (not decremented by 4)

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010        | vector 4 (illegal instr) @ 0x10

    | Seed test memory.
    move.l  #0x00008000, 0x00020000      | mem.L[0x20000] = 0x00008000
                                          | → word[0x20000]=0x0000, word[0x20002]=0x8000

    | ── TST.W -(A0) ── opword 0x4A60 ─────────────────────────────────
    move.l  #0x00020004, %a0             | A0 = 0x20004
    tst.w   -(%a0)                        | A0 → 0x20002, test word 0x8000
    bpl     _fail_flags                   | 0x8000 is negative → N must be set
    cmp.l   #0x00020002, %a0             | A0 must have decremented by 2
    bne     _fail_a0

    | ── TST.L -(A0) ── opword 0x4AA0 ─────────────────────────────────
    move.l  #0x00020004, %a0             | A0 = 0x20004
    tst.l   -(%a0)                        | A0 → 0x20000, test long 0x00008000
    cmp.l   #0x00020000, %a0             | A0 must have decremented by 4
    bne     _fail_al

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_flags:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0F1A, %d0
    move.l  %d0, (%a0)
_hf1:
    bra     _hf1

_fail_a0:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00A0, %d0
    move.l  %d0, (%a0)
_hf2:
    bra     _hf2

_fail_al:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD00A1, %d0
    move.l  %d0, (%a0)
_hf3:
    bra     _hf3

_illegal:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0             | vec-4 illegal-instruction trap — THE BUG
    move.l  %d0, (%a0)
_hf4:
    bra     _hf4
