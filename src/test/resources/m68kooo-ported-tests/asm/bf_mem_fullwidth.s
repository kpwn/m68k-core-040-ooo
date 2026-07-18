| bf_mem_fullwidth.s -- ROM-frontier memory bitfield forms.
| Covers:
|   BFEXTU (An){0:Dw},Dn with dynamic width 8 and 32.
|   BFINS  Dn,(An){0:32} static full-width store.
|   BFINS  Dn,(An){0:Dw} ROM dynamic width-8/24/32 stores.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.

    .text
    .org 0
_start:
    | Unaligned source exercises LSU split-load under the BFEXTU crack.
    move.l  #0x00012001, %a0
    move.l  #0x89abcdef, %d1
    move.l  %d1, (%a0)

    | Dynamic width 8: extract top byte from the memory long.
    moveq   #8, %d0
    bfextu  %a0@{0:%d0}, %d2
    bmi     1f
    bra     _fail
1:
    move.l  #0x00000089, %d3
    cmp.l   %d3, %d2
    beq     2f
    bra     _fail

2:
    | Dynamic width 32 via raw width 0x40: exact ROM shape e9d0 2020.
    moveq   #0x40, %d0
    bfextu  %a0@{0:%d0}, %d2
    bmi     3f
    bra     _fail
3:
    cmp.l   %d1, %d2
    beq     4f
    bra     _fail

4:
    | Static full-width destination exercises LSU split-store under BFINS.
    move.l  #0x00012007, %a1
    move.l  #0xffffffff, %d4
    move.l  %d4, (%a1)
    moveq   #0, %d2
    bfins   %d2, %a1@{0:32}
    beq     5f
    bra     _fail
5:
    move.l  (%a1), %d5
    cmp.l   %d2, %d5
    beq     6f
    bra     _fail

6:
    | Dynamic width 24: exact ROM shape efd1 2020 when D0[4:0] == 24.
    | Inserts low 24 bits into the top three bytes and preserves byte +3.
    move.l  #0x00012012, %a1
    move.l  #0xaabbccdd, %d4
    move.l  %d4, (%a1)
    move.l  #0x00657661, %d2
    moveq   #0x38, %d0
    bfins   %d2, %a1@{0:%d0}
    move.l  (%a1), %d5
    move.l  #0x657661dd, %d6
    cmp.l   %d6, %d5
    beq     8f
    bra     _fail

8:
    | Dynamic width 8: current ROM frontier efd1 2020 with D0[4:0] == 8.
    | Inserts low 8 bits into byte +0 and preserves bytes +1..+3.
    move.l  #0x0001201e, %a1
    move.l  #0xaabbccdd, %d4
    move.l  %d4, (%a1)
    move.l  #0x12345680, %d2
    moveq   #0x28, %d0
    bfins   %d2, %a1@{0:%d0}
    bpl     _fail
    beq     _fail
    bvs     _fail
    bcs     _fail
    move.l  (%a1), %d5
    move.l  #0x80bbccdd, %d6
    cmp.l   %d6, %d5
    beq     9f
    bra     _fail

9:
    | Dynamic width 32 via raw width 0x40: the same ROM opcode also hits
    | this path and must overwrite the full long.
    move.l  #0x00012018, %a1
    move.l  #0xaabbccdd, %d4
    move.l  %d4, (%a1)
    move.l  #0x6361676f, %d2
    moveq   #0x40, %d0
    bfins   %d2, %a1@{0:%d0}
    move.l  (%a1), %d5
    cmp.l   %d2, %d5
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
