| flags_test.s — CCR flag-after-ALU test
|
| Exercises the CCR update path: an ADD that produces Z=1, then BEQ taken.
| Also tests BNE not-taken when Z=1.
|
| Since CCR lacks physical renaming, we serialise: the ADD must commit
| (updating arch_ccr) before BEQ is issued from iq_int.  We add several
| independent LEA/MOVE instructions between them so the ADD pipeline can
| complete and commit before BEQ issues.
|
| Sequence:
|   D0 = 0x80000000
|   D1 = 0x80000000
|   D0 = D0 + D1         → 0x100000000 = 0 (mod 32), Z=1, C=1
|   (several NOPs worth of LEA to let ADD commit)
|   BEQ _zero            → taken (Z=1)
|   FAIL store           → should not reach here
| _zero:
|   BNE _fail            → NOT taken (Z=1)
|   PASS store

    .text
    .org 0

_start:
    move.l  #0x80000000, %d0    | D0 = 0x80000000
    move.l  #0x80000000, %d1    | D1 = 0x80000000
    add.l   %d1, %d0            | D0 = 0 (overflow+carry), Z=1
    | Burn several cycles so ADD commits and arch_ccr is updated:
    | LEA is a 3-cycle path (decode→iq_int→ALU→CDB→commit)
    lea     0x00100000, %a0     | padding to let ADD commit
    lea     0x00200000, %a1     | padding
    lea     0x00300000, %a2     | padding

    beq     _zero               | Z=1 → taken (PASS path)

    | ── FAIL: BEQ not taken when Z=1 ──────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_zero:
    bne     _halt_fail          | Z=1 → NOT taken (correct), fall through

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)          | PASS store
_halt:
    stop    #0x2700
    bra     _halt
