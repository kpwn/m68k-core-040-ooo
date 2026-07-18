| scc_mem_forms.s -- Scc byte stores to common memory EAs
|
| Covers Scc memory destinations that now crack as ALU_SCC -> STORE.B:
|   (An), (d16,An), (xxx).W, and (xxx).L.
|
| The checks verify the stored byte value, neighbour-byte preservation,
| and that Scc preserves CCR for both true and false conditions.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    lea     0x00105000, %a0

    | (An): SEQ true writes 0xff at the addressed byte and preserves Z.
    move.l  #0x11223344, (%a0)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1
    seq     (%a0)
    bne     _fail1                 | Scc must preserve Z=1
    move.l  (%a0), %d0
    cmp.l   #0xff223344, %d0
    bne     _fail1

    | (d16,An): SNE false writes 0x00 at the displaced byte.
    move.l  #0xaabbccdd, 0x20(%a0)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so NE is false
    sne     0x21(%a0)
    bne     _fail2                 | Scc must preserve Z=1
    move.l  0x20(%a0), %d1
    cmp.l   #0xaa00ccdd, %d1
    bne     _fail2

    | (xxx).W: SNE true writes 0xff through an absolute-short EA.
    move.l  #0x01020304, 0x7000.w
    moveq   #1, %d5
    tst.l   %d5                    | Z=0, so NE is true
    sne     0x7000.w
    beq     _fail3                 | Scc must preserve Z=0
    lea     0x7000.w, %a1
    move.l  (%a1), %d2
    cmp.l   #0xff020304, %d2
    bne     _fail3

    | (xxx).L: SLE true writes one byte inside the long-absolute object.
    move.l  #0x55667788, 0x00108000.l
    moveq   #5, %d5
    cmpi.l  #6, %d5                | signed less-than, LE is true
    sle     0x00108001.l
    bpl     _fail4                 | Scc must preserve N=1 from CMP
    lea     0x00108000, %a1
    move.l  (%a1), %d3
    cmp.l   #0x55ff7788, %d3
    bne     _fail4

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_halt_fail1:
    bra     _halt_fail1

_fail2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_halt_fail2:
    bra     _halt_fail2

_fail3:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_halt_fail3:
    bra     _halt_fail3

_fail4:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_halt_fail4:
    bra     _halt_fail4
