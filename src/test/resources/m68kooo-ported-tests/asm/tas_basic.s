| tas_basic.s — Test for TAS Dn and TAS (An) / (xxx).L forms.
|
| TAS semantics (PRM §4.189):
|   - Sets CCR.N from bit 7 of operand BEFORE the set.
|   - Sets CCR.Z if low byte BEFORE the set is 0.
|   - Clears V and C.
|   - Preserves X.
|   - Sets bit 7 of operand (byte OR 0x80).
|
| Memory-form tests use register-indirect addressing exclusively for the
| verification LOAD that follows the STORE, to match LSU ordering
| semantics (see iq_mem.v: abs-addr STOREs don't alias-block register-
| indirect LOADs to the same byte because the bitmap compares phys
| pbase/disp tuples, not final EAs).  Verification reads therefore reuse
| the (An) form established by the write.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | Test 1: TAS D0 with D0.b = 0x00 — expect Z=1 N=0, D0.b = 0x80
    move.l  #0x12345600, %d0
    tas     %d0
    bne     _fail                    | Z must be 1
    bmi     _fail                    | N must be 0
    move.l  #0x12345680, %d1
    cmp.l   %d1, %d0
    bne     _fail

    | Test 2: TAS D2 with D2.b = 0x7F — expect Z=0 N=0, D2.b = 0xFF
    move.l  #0xAABBCC7F, %d2
    tas     %d2
    beq     _fail                    | Z must be 0
    bmi     _fail                    | N must be 0
    move.l  #0xAABBCCFF, %d1
    cmp.l   %d1, %d2
    bne     _fail

    | Test 3: TAS D3 with D3.b = 0x80 — expect Z=0 N=1, D3.b = 0x80
    move.l  #0xDEADBE80, %d3
    tas     %d3
    beq     _fail                    | Z must be 0
    bpl     _fail                    | N must be 1
    move.l  #0xDEADBE80, %d1
    cmp.l   %d1, %d3
    bne     _fail

    | Test 4: TAS (An) memory form at 0x50000, initial byte 0x42.
    lea     0x50000, %a0
    move.b  #0x42, (%a0)
    tas     (%a0)
    beq     _fail                    | Z must be 0 (0x42 != 0)
    bmi     _fail                    | N must be 0 (bit7=0)
    move.b  (%a0), %d4
    andi.l  #0xFF, %d4
    cmpi.l  #0xC2, %d4
    bne     _fail

    | Test 5: TAS (An) memory form at 0x50010, initial byte 0x00 (Z path).
    lea     0x50010, %a1
    move.b  #0x00, (%a1)
    tas     (%a1)
    bne     _fail                    | Z must be 1
    bmi     _fail                    | N must be 0
    move.b  (%a1), %d5
    andi.l  #0xFF, %d5
    cmpi.l  #0x80, %d5
    bne     _fail

    | Test 6: TAS (An)+ — byte at 0x50020 becomes 0xFF, A2 += 1.
    lea     0x50020, %a2
    move.b  #0x7F, (%a2)
    tas     (%a2)+
    beq     _fail                    | Z=0 (0x7F is nonzero)
    bmi     _fail                    | N=0 (bit7=0 before OR)
    move.l  %a2, %d0
    cmpi.l  #0x50021, %d0            | A2 must be post-incremented by 1
    bne     _fail
    subq.l  #1, %a2
    move.b  (%a2), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0xFF, %d0
    bne     _fail

    | Test 7: TAS -(An) — byte at 0x50031 becomes 0x80, A3 ends at 0x50031.
    lea     0x50031, %a3
    move.b  #0x00, (%a3)
    addq.l  #1, %a3                   | a3 = 0x50032
    tas     -(%a3)
    bne     _fail                    | Z=1 (orig byte was 0x00)
    bmi     _fail                    | N=0
    move.l  %a3, %d0
    cmpi.l  #0x50031, %d0            | A3 must be pre-decremented
    bne     _fail
    move.b  (%a3), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x80, %d0
    bne     _fail

    | Test 8: TAS (d16,An) — byte at 0x50100 + 0x10 = 0x50110.
    lea     0x50100, %a4
    move.b  #0x05, (0x10,%a4)
    tas     (0x10,%a4)
    beq     _fail
    bmi     _fail
    move.b  (0x10,%a4), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x85, %d0
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
