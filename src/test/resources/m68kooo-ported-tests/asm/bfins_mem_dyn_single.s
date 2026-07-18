| bfins_mem_dyn_single.s — BFINS Dn,<mem>{Do:Dw} with exactly one
| dynamic side.  Exercises the V2 task #205 (B4) BFINS memory
| dyn-single crack:
|   phase 0: LOAD long (ea) -> TMP1
|   phase 1: ALU_BFINS src_a=TMP1, src_b=Dn_insert, src_c=Dn_dyn
|            with imm[13]=1 ("dyn via src_c") -> TMP1
|   phase 2: STORE long TMP1 -> (ea)
|
| The 3-read hazard for BFINS (src_a=mem, src_b=insert, src_c=dyn)
| lands on one ALU uop thanks to the src_c port added by A5.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    move.l  #0x40800300, %a0
    move.l  #0x00000000, (%a0)

    | ---- BFINS D0,(A0){#8:D1} — dyn-wid=16, insert 0x5a5a into [23..8] ----
    move.l  #0x00005a5a, %d0
    moveq   #16, %d1
    bfins   %d0, (%a0){#8:%d1}
    move.l  (%a0), %d3
    cmp.l   #0x005a5a00, %d3
    bne     _fail

    | ---- BFINS D0,(A0){D1:#8} — dyn-off=4, width=8, insert 0xAA ----
    | mask_base = 0xFF << 24 = 0xFF000000; ROR(mask_base,4) = 0x0FF00000.
    | ins_shl = 0xAA << 24 = 0xAA000000; ROR(ins_shl,4) = 0x0AA00000.
    | (0x005a5a00 & ~0x0FF00000) | 0x0AA00000 = 0x000A5A00 | 0x0AA00000
    | = 0x0AAA5A00.
    move.l  #0x000000aa, %d0
    moveq   #4, %d1
    bfins   %d0, (%a0){%d1:#8}
    move.l  (%a0), %d3
    cmp.l   #0x0aaa5a00, %d3
    bne     _fail

    | ---- BFINS D0,(A0){#0:D1} — dyn-wid=32, full overwrite ----
    move.l  #0xdeadbeef, %d0
    moveq   #0, %d1       | 0 → 32
    bfins   %d0, (%a0){#0:%d1}
    move.l  (%a0), %d3
    cmp.l   #0xdeadbeef, %d3
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
