| bcc_all_conditions.s — All 14 Bcc conditions + BRA covered in one run
|
| Note: condition codes 0000 and 0001 are BRA and BSR (not BT/BF for
| Bcc — those apply only to DBcc/Scc).  This test therefore sweeps:
|   BRA, BHI, BLS, BCC, BCS, BNE, BEQ, BVC, BVS, BPL, BMI, BGE, BLT,
|   BGT, BLE  — exercising the `cc_true` truth table for every value
|   of flags_rd_mask[3:0] ∈ {0, 2..15}.
|
| Each stage sets up a definitive CCR state and then takes ONE Bcc
| that MUST branch; if it falls through, we jump to _fail.  The branch
| chain runs _s0 → _s1 → ... → _s14 → _pass.

    .text
    .org 0

_start:
    | stage 0: BRA (unconditional)
    bra     _s1

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_s1:
    | BHI: C=0, Z=0.  CMP 2 - 1 → C=0, Z=0.
    moveq   #1, %d0
    moveq   #2, %d1
    cmp.l   %d0, %d1
    bhi     _s2
    bra     _fail
_s2:
    | BLS: C=1 OR Z=1.  CMP 1 - 2 → C=1 (borrow).
    moveq   #2, %d0
    moveq   #1, %d1
    cmp.l   %d0, %d1
    bls     _s3
    bra     _fail
_s3:
    | BCC: C=0.  ADD 1+1 = 2.
    moveq   #1, %d0
    addq.l  #1, %d0
    bcc     _s4
    bra     _fail
_s4:
    | BCS: C=1.  ADD 0xFFFFFFFF + 1.
    move.l  #0xFFFFFFFF, %d0
    addq.l  #1, %d0
    bcs     _s5
    bra     _fail
_s5:
    | BNE: Z=0.  CMP 1 vs 2.
    moveq   #1, %d0
    moveq   #2, %d1
    cmp.l   %d0, %d1
    bne     _s6
    bra     _fail
_s6:
    | BEQ: Z=1.  CMP 7 vs 7.
    moveq   #7, %d0
    cmp.l   %d0, %d0
    beq     _s7
    bra     _fail
_s7:
    | BVC: V=0.  ADD 1+2.
    moveq   #1, %d0
    moveq   #2, %d1
    add.l   %d1, %d0
    bvc     _s8
    bra     _fail
_s8:
    | BVS: V=1.  ADD 0x7FFFFFFF + 1 → signed overflow.
    move.l  #0x7FFFFFFF, %d0
    addq.l  #1, %d0
    bvs     _s9
    bra     _fail
_s9:
    | BPL: N=0.  TST positive value.
    move.l  #0x00000001, %d0
    tst.l   %d0
    bpl     _s10
    bra     _fail
_s10:
    | BMI: N=1.  TST negative value.
    move.l  #0x80000000, %d0
    tst.l   %d0
    bmi     _s11
    bra     _fail
_s11:
    | BGE: (N==V).  SUB 5-3 = 2 → N=0, V=0.
    moveq   #5, %d0
    moveq   #3, %d1
    sub.l   %d1, %d0
    bge     _s12
    bra     _fail
_s12:
    | BLT: (N!=V).  SUB 3-5 = -2 → N=1, V=0.
    moveq   #3, %d0
    moveq   #5, %d1
    sub.l   %d1, %d0
    blt     _s13
    bra     _fail
_s13:
    | BGT: (N==V) && Z=0.  SUB 5-3 = 2 → N=0, V=0, Z=0.
    moveq   #5, %d0
    moveq   #3, %d1
    sub.l   %d1, %d0
    bgt     _s14
    bra     _fail
_s14:
    | BLE: (N!=V) || Z=1.  SUB 5-5 = 0 → Z=1.
    moveq   #5, %d0
    sub.l   %d0, %d0
    ble     _pass
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
