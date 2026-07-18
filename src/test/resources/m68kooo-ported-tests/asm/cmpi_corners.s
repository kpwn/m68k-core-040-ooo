| cmpi_corners.s — CMPI.L corner-case CCR generation
|
| Hypothesis: CMPI.L #imm,Dn computes Dn − imm (discarding result) and
| sets NZVC per PRM §3.3 subtract semantics:
|   C = borrow from MSB
|   V = (sign(Dn) ≠ sign(imm)) ∧ (sign(Dn) ≠ sign(result))
|   Z = (result == 0)
|   N = result[31]
|
| Corner values of interest: 0, MIN_INT (0x80000000), MAX_INT
| (0x7FFFFFFF), and -1 (0xFFFFFFFF).
|
| Case 1: D0=0 vs 0             → result 0,  Z=1, C=0, V=0, N=0 (BEQ)
| Case 2: D0=0x80000000 vs self → result 0,  Z=1                (BEQ)
| Case 3: D0=0x7FFFFFFF vs 0x80000000
|         = 2^31-1 − (−2^31) signed → overflow. Raw = 0xFFFFFFFF,
|           N=1, V=1, C=1, Z=0.  Signed: D0 > imm → BGT taken.
| Case 4: D0=0xFFFFFFFF vs 1
|         = −1 − 1 signed = −2.  Raw = 0xFFFFFFFE, N=1, C=0, V=0, Z=0.
|         BLT taken (−1 < 1), BCS not taken (unsigned 0xFFFFFFFF > 1 → C=0).
| Case 5: D0=1 vs 0xFFFFFFFF
|         = 1 − (−1) signed = 2.  Raw = 0x00000002 with borrow → C=1.
|         N=0, V=0, Z=0.  Signed: 1 > −1 → BGT taken; unsigned 1 < 0xFFFFFFFF → BCS taken.

    .text
    .org 0

_start:
    | Case 1: 0 vs 0 → BEQ
    move.l  #0x00000000, %d0
    cmpi.l  #0x00000000, %d0
    bne     _fail

    | Case 2: 0x80000000 vs 0x80000000 → BEQ
    move.l  #0x80000000, %d0
    cmpi.l  #0x80000000, %d0
    bne     _fail

    | Case 3: 0x7FFFFFFF vs 0x80000000 → BGT taken (signed), BCS taken
    move.l  #0x7FFFFFFF, %d0
    cmpi.l  #0x80000000, %d0
    ble     _fail                    | signed: MAX > MIN
    bcc     _fail                    | unsigned: 0x7FFF.. < 0x8000.. → C=1

    | Case 4: 0xFFFFFFFF vs 0x00000001 → BLT (signed), BCC taken (unsigned)
    move.l  #0xFFFFFFFF, %d0
    cmpi.l  #0x00000001, %d0
    bge     _fail                    | signed: -1 < 1
    bcs     _fail                    | unsigned: 0xFFFF.. > 1 → C=0

    | Case 5: 0x00000001 vs 0xFFFFFFFF → BGT (signed), BCS (unsigned)
    move.l  #0x00000001, %d0
    cmpi.l  #0xFFFFFFFF, %d0
    ble     _fail                    | signed: 1 > -1
    bcc     _fail                    | unsigned: 1 < 0xFFFF.. → C=1

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
