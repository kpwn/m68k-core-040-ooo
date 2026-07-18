| bchg_static_dn_long.s — BCHG #n,Dn static long-form corner
|
| PRM §4.15 BCHG (Test a bit and change): for Dn destinations the bit
| number is mod 32, operand is .L.  Z = NOT(old_bit), then the bit is
| toggled in Dn.  N/V/C/X are not affected.
|
| Covers the V2 Stage D-4 Dn-direct static shape: 1 µop with
| uop_op=ALU_BCHG, imm=ext1[4:0], has_src_a=Dn, has_dst=Dn, flags_wr={Z}.
|
| Almost-got-wrong corner the D-4 agent surfaced while implementing:
| BCHG #31,Dn must act on bit 31 (the sign bit).  Masking `ext1` to
| 4 bits instead of 5 on the Dn-direct path would land the operation
| on bit 15 and silently miscorrupt D0.  Using `src_displacement[4:0]`
| (not `[2:0]` which is correct for byte-operand mem dsts only) is
| what this test pins down.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Case 1: BCHG #31,D0 — D0 starts all-zero.
    | Expected: D0 = 0x80000000, CCR.Z = 1 (old bit was 0).
    moveq   #0, %d0
    bchg    #31, %d0
    bne     _fail                      | CCR.Z from BCHG should be 1
    cmp.l   #0x80000000, %d0
    bne     _fail

    | Case 2: BCHG #31,D0 again — toggles bit 31 back to 0.
    | Expected: D0 = 0x00000000, CCR.Z = 0 (old bit was 1).
    bchg    #31, %d0
    beq     _fail                      | CCR.Z from BCHG should be 0
    cmp.l   #0x00000000, %d0
    bne     _fail

    | Case 3: BCHG #0,D7 — flip bit 0 (into D7=0x2, gets D7=0x3, Z=1).
    moveq   #0x2, %d7
    bchg    #0, %d7
    bne     _fail                      | Z=1 (old bit 0 was 0)
    cmp.l   #0x3, %d7
    bne     _fail

    | Case 4: BCHG #0,D7 again — clears bit 0 (D7=0x2, Z=0).
    bchg    #0, %d7
    beq     _fail                      | Z=0 (old bit 0 was 1)
    cmp.l   #0x2, %d7
    bne     _fail

    | Case 5: BCHG #16,D3 — mid-range bit to catch off-by-one masks.
    | 0xdeadbeef: low word 0xbeef (no bit 16); the 0xdead nibbles are
    | the upper half, and bit 16 of 0xdeadbeef is bit 0 of 0xdead = 1.
    move.l  #0xdeadbeef, %d3
    bchg    #16, %d3                   | toggles bit 16 from 1→0
    beq     _fail                      | Z=0 (old bit was 1)
    cmp.l   #0xdeacbeef, %d3
    bne     _fail

    | Case 6: pre-existing N/V/C/X must be untouched by BCHG.
    | Set up a known CCR via ADDI that sets NZVCX, then confirm BCHG
    | only touches Z (N stays).
    moveq   #-1, %d2                    | D2 = 0xffffffff
    add.l   %d2, %d2                    | 0xffffffff + 0xffffffff = 0xfffffffe,
                                         | carry out → C=X=1, N=1, Z=0, V=0.
    bpl     _fail                       | expect N=1
    bchg    #4, %d2                     | toggles bit 4 (was 1), Z=0
    bpl     _fail                       | N must still be 1 (not clobbered)
    | D2 was 0xfffffffe with bit 4 set (1111_1110); toggling gives 0xffffffee.
    cmp.l   #0xffffffee, %d2
    bne     _fail

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
