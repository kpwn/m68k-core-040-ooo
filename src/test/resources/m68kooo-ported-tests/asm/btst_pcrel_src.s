| btst_pcrel_src.s — BTST Dm,(d16,PC) byte PC-relative target
|
| PRM §4.16 BTST: uniquely among the bit-ops, BTST's target EA may
| be (d16,PC) or (d8,PC,Xn) or #imm — the other three (BCHG/BCLR/BSET)
| require data-alterable EAs and therefore reject PC-rel / immediate.
|
| Covers the V2 Stage D-4 BTST dynamic-src + PC-rel target crack:
|   Phase 0: TMP2 = Dm AND 7             (ALU_AND, mod-8 bit index)
|   Phase 1: TMP1 = byte load at (pd_pc+2+disp), is_abs=1
|   Phase 2: BTST TMP1,TMP2              (ALU_BTST, flags_wr={Z})
|
| Corner the D-4 agent almost got wrong: for PC-rel targets the load
| phase must supply pd_pc+2+displacement as the absolute address
| (is_abs=1, has_src_a=0) — NOT plumb an arch register.  A naïve
| reuse of the (d16,An) shape would drive arch_src_a from dst_base_reg
| (= REG_TMP0 for PC-rel) with imm=displacement, producing an
| effective address of just the raw displacement.
|
| We place a byte constant at a label, and BTST a known bit via PC-rel.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | 0xA5 = 0b1010_0101 at label `pattern`.
    moveq   #0, %d0
    btst    %d0, pattern(%pc)          | bit 0 of 0xA5 = 1 → Z=0
    beq     _fail

    moveq   #1, %d0
    btst    %d0, pattern(%pc)          | bit 1 of 0xA5 = 0 → Z=1
    bne     _fail

    moveq   #7, %d0
    btst    %d0, pattern(%pc)          | bit 7 of 0xA5 = 1 → Z=0
    beq     _fail

    | mod-8 check: Dm=10 should act as bit 2 of 0xA5 (10 mod 8 = 2).
    | 0xA5 bit 2 = 1 → Z=0.
    moveq   #10, %d0
    btst    %d0, pattern(%pc)
    beq     _fail

    | Static-number BTST also works for PC-rel.
    btst    #1, pattern(%pc)           | bit 1 = 0 → Z=1
    bne     _fail
    btst    #5, pattern(%pc)           | 0xA5 bit 5 = 1 → Z=0
    beq     _fail

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

pattern:
    .byte 0xA5
    .byte 0x00                         | padding, keep code aligned
