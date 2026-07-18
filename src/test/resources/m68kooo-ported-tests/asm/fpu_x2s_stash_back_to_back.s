| fpu_x2s_stash_back_to_back.s -- FMOVE.S FPn,(An) back-to-back stash race.
|
| Bug found via fuzz-corpus widening (2026-07-11, fuzz seed 1832541370):
| decode_1111.vh's "FMOVE.S FPn,(An)" (FP register -> memory) is a
| 3-µop crack: phase 0 (FPU_X2S) converts fp_prf[FPn] to a 32-bit
| single and stashes it in fpu_top's SHARED x2s_stash_r register at
| *execution* time (mov_valid_r, which can fire ahead of ROB
| retirement); phase 1 (SYS_X2S_TO_INT) reads x2s_stash_r at *retire*
| time (in-order) and broadcasts it into PRF[REG_TMP1] for phase 2's
| STORE.L.
|
| Two FMOVE.S FPn,(An) macro-ops in flight close together can race:
| the SECOND one's X2S execution can overwrite x2s_stash_r before the
| FIRST one's SYS_X2S_TO_INT has retired and consumed it -- so the
| first store silently gets the SECOND op's converted value instead of
| its own.  Confirmed via fuzz seed 1832541370: the RTL's wrongly
| stored bytes exactly matched the SECOND FMOVE.S's computed single,
| byte for byte.  (fpu_fsqrt_basic.s's header comment already flagged
| this exact risk: "Multiple back-to-back FSQRT+FMOVE.S in a single
| program would race on the single x2s_stash_r register inside
| fpu_top ... with no serialisation between concurrent X2S
| micro-ops" -- this test is the first to actually exercise it.)
|
| Fix: FPU_X2S dispatch now drains the ROB first (m68k_core_fetch.vh's
| q_rob_drain_req, same idiom already used for BR_FBCC/CCR reads of a
| shared architectural side-channel) -- guaranteeing any older X2S/
| SYS_X2S_TO_INT pair has fully retired (and thus consumed
| x2s_stash_r) before a new FPU_X2S can overwrite it.
|
| Note: a small hand-written repro (two back-to-back FMOVE.S FPn,(An)
| stores with no filler) did NOT reproduce the race -- the exact
| OoO/ROB-occupancy timing needed to make the two X2S executions
| genuinely overlap depends on there being enough OTHER in-flight
| instructions ahead of them, which a tiny directed test doesn't
| naturally create.  This test is therefore the trimmed body of the
| actual failing fuzz program (seed 1832541370, up through the two
| FMOVE.S stores), with the random continuation replaced by an
| explicit compare/PASS/FAIL tail -- verified to reproduce the
| corruption on the pre-fix RTL and pass cleanly post-fix.
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0FA1 -- first store (FNEG result, at A2) wrong/corrupted
|   0xDEAD0FA2 -- second store (FABS result, at A3) wrong/corrupted

    .text
    .org 0

_start:
    lea     0x00080000, %a7   | stack top
    lea     0x00100000, %a0   | A0 data pool
    lea     0x00104000, %a1   | A1 data pool
    lea     0x00108000, %a2   | A2 data pool
    lea     0x0010c000, %a3   | A3 data pool
    move.l  #0x5abecd43, %d0
    move.l  #0x0000a68f, %d1
    move.l  #0x0000859c, %d2
    move.l  #0x00000000, %d3
    movea.l #0xdeadbeef, %a4
    movea.l #0x80000000, %a5
    movea.l #0xed1546f5, %a6
    moveq   #3, %d6           | D6 = hop counter
    moveq   #0, %d4
    moveq   #0, %d5

    | Filler matching the original fuzz seed's instruction mix -- this
    | keeps enough older work in flight for the ROB to be non-empty
    | when the two FMOVE.S FPn,(An) stores below dispatch, which is
    | what let the x2s_stash race actually manifest.
    sub.l   %d3, %d3
    lea     0x00100000, %a5
    adda.l  -(%a0), %a5
    cmp.l   -(%a1), %d1
    cmpi.w  #0xdccc, (80,%a3)
    lea     (676,%a0), %a4
    lea     (928,%a0), %a5
    lea     (708,%a0), %a6
    move.l  %a5, (%a6)
    lea     (928,%a0), %a6
    move.l  #0x0000025d, (%a6)
    .word   0x02B4, 0x6A70, 0x3ABC, 0x0164, 0x0020
    move.l  #0xfa0a1c1c, %d0
    and.l   (%a0)+, %d0
    bgt     fwd_1
    sub.l   %d2, %d5
    asl.l   #3, %d1
fwd_1:
    lea     (196,%a0), %a4
    lea     (148,%a1), %a5
    move.l  (%a4), (%a5)+
    negx.l  %d3
    move.l  #0x52124493, %d5
    cmp.l   (%a1)+, %d4
    lea     0x00100000, %a5
    adda.w  %a1, %a5
    move.l  #0x00000099, %d5
    lea     (492,%a0), %a4
    lea     (828,%a0), %a5
    lea     (524,%a0), %a6
    move.l  %a5, (%a6)
    lea     (828,%a0), %a6
    move.l  #0x0000c253, (%a6)
    move.l  #0x7fffffff, %d6
    move.l  #0x00000000, %d1
    .word   0xDCB4, 0x1921, 0x0020
    moveq   #-92, %d0
    smi     %d1
    nop
    beq     fwd_2

    | FP4 := FNEG.X(-62564.0) == +62564.0 == 0x47746400 single.
    move.l  #0xc7746400, %d4
    fmove.s %d4, %fp0
    fneg.x  %fp0, %fp4
    fmove.s %fp4, (%a2)
    move.l  #0x00000005, %d2

    | Immediately (no filler): FP7 := FABS.X(-26659.0) == +26659.0 ==
    | 0x46d04600 single -- a DIFFERENT value at a DIFFERENT address.
    move.l  #0xc6d04600, %d4
    fmove.s %d4, %fp2
    fabs.x  %fp2, %fp7
    fmove.s %fp7, (%a3)
fwd_2:
    | Verify both stores landed their OWN value, not swapped/corrupted.
    move.l  (%a2), %d0
    cmp.l   #0x47746400, %d0
    bne     _fail_first

    move.l  (%a3), %d1
    cmp.l   #0x46d04600, %d1
    bne     _fail_second

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

_fail_first:
    lea     0xFFFF0000, %a1
    move.l  #0xDEAD0FA1, %d2
    move.l  %d2, (%a1)
_halt_first:
    bra     _halt_first

_fail_second:
    lea     0xFFFF0000, %a1
    move.l  #0xDEAD0FA2, %d2
    move.l  %d2, (%a1)
_halt_second:
    bra     _halt_second
