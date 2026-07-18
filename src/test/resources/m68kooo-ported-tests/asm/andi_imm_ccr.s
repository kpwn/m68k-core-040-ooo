| andi_imm_ccr.s — task #236 / F4 — ANDI #imm.B,CCR via V2 sysop family.
|
| Verifies SYS_ANDI_CCR retire path: arch CCR ← arch CCR & imm[7:0].
| User-mode legal (no privilege check).  Single-µop UOP_SYS / SYS_ANDI_CCR.
|
| Pipeline note: SYS_*_CCR forces a flush+redirect at retire (see
| commit.v F4 block) so subsequent µops dispatch AFTER the restore-port
| writeback has propagated into ccr_prf — this is what lets a directly-
| following Bcc observe the updated CCR.
|
| Recap CCR layout (PRM §1.3): bits [4:0] = X N Z V C.
|   X = bit 4 (mask 0x10), N = bit 3 (0x08), Z = bit 2 (0x04),
|   V = bit 1 (0x02),      C = bit 0 (0x01).
|
| Sequence:
|   1. MOVE.W #0x001F,CCR   — set X=N=Z=V=C=1.
|   2. ANDI    #0xF0,CCR    — keep X (bit 4), clear N/Z/V/C.
|   3. Bcc validations:     N=0 → BMI no-branch.  Z=0 → BEQ no-branch.
|                           V=0 → BVS no-branch.  C=0 → BCS no-branch.
|
|   4. ANDI    #0x10,CCR    — already have X only (post step-2); idempotent
|                              re-AND, all of NZVC stay 0.  Tests AND
|                              with mask that doesn't change CCR.
|   5. ANDI    #0x00,CCR    — clear all 5 bits including X.
|   6. Verify X cleared via ADDX: %d0=0, %d1=0, ADDX → 0+0+X = 0.
|
| PASS: 0xC0FFEE00.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Phase 1: set all 5 CCR bits via MOVE-CCR.
    move.w  #0x001F, %ccr            | X=N=Z=V=C=1.

    | Phase 2: ANDI #0xF0,CCR — keep X (bit 4), clear NZVC.
    .word   0x023C, 0x00F0           | ANDI #0xF0,CCR

    | Phase 3: confirm NZVC all cleared by ANDI.
    bmi     _fail                    | N=0.
    beq     _fail                    | Z=0.
    bvs     _fail                    | V=0.
    bcs     _fail                    | C=0.

    | Phase 4: ANDI #0x10,CCR — keep X, mask leaves NZVC clear.
    .word   0x023C, 0x0010           | ANDI #0x10,CCR

    bmi     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail

    | Phase 5: ANDI #0x00,CCR — clear ALL 5 bits including X.
    .word   0x023C, 0x0000           | ANDI #0x00,CCR

    | Phase 6: ADDX confirms X cleared.  d0=0, d1=0, ADDX.L %d0,%d1.
    | If X had been preserved erroneously (= 1), d1 would become 1.
    moveq   #0, %d0
    moveq   #0, %d1
    addx.l  %d0, %d1                 | d1 = 0 + 0 + X.
    cmp.l   #0, %d1
    bne     _fail                    | X cleared → d1=0; else fail.

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
