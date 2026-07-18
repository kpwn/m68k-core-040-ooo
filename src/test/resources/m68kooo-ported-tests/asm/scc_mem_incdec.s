| scc_mem_incdec.s -- Scc byte stores with address-register update
|
| Covers Scc (An)+ and Scc -(An), including the 68k A7 byte step of 2.
| Checks true/false byte values, neighbour-byte preservation, address
| update order, and CCR preservation.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Postincrement with a normal address register: store at old A0,
    | then advance by one byte.
    lea     0x00109000, %a0
    move.l  #0x11223344, (%a0)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so EQ is true
    seq     (%a0)+
    bne     _fail1                 | Scc must preserve Z=1
    cmpa.l  #0x00109001, %a0
    bne     _fail1
    lea     0x00109000, %a1
    move.l  (%a1), %d0
    cmp.l   #0xff223344, %d0
    bne     _fail1

    | Postincrement with A7 byte destination: false condition stores 0,
    | and A7 advances by two bytes.
    lea     0x00109100, %a7
    move.l  #0xaabbccdd, (%a7)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so NE is false
    sne     (%a7)+
    bne     _fail2                 | Scc must preserve Z=1
    cmpa.l  #0x00109102, %a7
    bne     _fail2
    lea     0x00109100, %a1
    move.l  (%a1), %d1
    cmp.l   #0x00bbccdd, %d1
    bne     _fail2

    | Predecrement with a normal address register: decrement first, then
    | store at the new address.
    lea     0x00109202, %a2
    lea     0x00109200, %a1
    move.l  #0x55667788, (%a1)
    moveq   #1, %d5
    tst.l   %d5                    | Z=0, so NE is true
    sne     -(%a2)
    beq     _fail3                 | Scc must preserve Z=0
    cmpa.l  #0x00109201, %a2
    bne     _fail3
    move.l  (%a1), %d2
    cmp.l   #0x55ff7788, %d2
    bne     _fail3

    | Predecrement with A7 byte destination: decrement by two first, then
    | false condition stores 0 at the new A7.
    lea     0x00109302, %a7
    lea     0x00109300, %a1
    move.l  #0x99aabbcc, (%a1)
    moveq   #0, %d5
    tst.l   %d5                    | Z=1, so NE is false
    sne     -(%a7)
    bne     _fail4                 | Scc must preserve Z=1
    cmpa.l  #0x00109300, %a7
    bne     _fail4
    move.l  (%a1), %d3
    cmp.l   #0x00aabbcc, %d3
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
