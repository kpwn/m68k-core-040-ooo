| bfins_mem_dyn_both.s — BFINS Dn,<mem>{Do:Dw} with BOTH offset and
| width dynamic.  The famous 4-source case (old_mem, Dn_insert,
| Dn_off, Dn_wid) resolved via V2 task #205 (B4) 4-phase crack:
|   phase 0: ALU_BF_PACK Dn_off, Dn_wid -> TMP2
|   phase 1: LOAD long (ea) -> TMP1
|   phase 2: ALU_BFINS src_a=TMP1, src_b=Dn_insert, src_c=TMP2,
|            imm=dyn-both+via-src-c -> TMP1
|   phase 3: STORE long TMP1 -> (ea)
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800300, %a0
    move.l  #0x00000000, (%a0)

    | ---- BFINS D0,(A0){D1:D2} — offset=8, width=16, insert 0xbeef ----
    | bits [8..23] = low 16 bits of D0 = 0xbeef -> 0x00beef00.
    move.l  #0x0000beef, %d0
    moveq   #8, %d1
    moveq   #16, %d2
    bfins   %d0, (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0x00beef00, %d3
    bne     _fail

    | ---- BFINS D0,(A0){D1:D2} — offset=0, width=0(=32), full ----
    move.l  #0xdeadbeef, %d0
    moveq   #0, %d1
    moveq   #0, %d2
    bfins   %d0, (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0xdeadbeef, %d3
    bne     _fail

    | ---- BFINS D0,(A0){D1:D2} — offset=12, width=4, insert 0x7 ----
    | mask_base = 0xffffffff << (32-4) = 0xf0000000.
    | ROR(mask_base, 12) = 0x000f0000.  ins_shl = 0x7 << 28 = 0x70000000.
    | ROR(0x70000000, 12) = 0x00070000.
    | (0xdeadbeef & ~0x000f0000) | 0x00070000
    |   = (0xdeadbeef & 0xfff0ffff) | 0x00070000
    |   = 0xdea0beef | 0x00070000 = 0xdea7beef.
    moveq   #7, %d0
    moveq   #12, %d1
    moveq   #4, %d2
    bfins   %d0, (%a0){%d1:%d2}
    move.l  (%a0), %d3
    cmp.l   #0xdea7beef, %d3
    beq     _pass
    bra     _fail

_pass:
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
