| suba_mem_forms.s -- SUBA.L <ea>,An memory-source forms
|
| Covers the ROM sizing-walker shape at 0x4084bbd8:
|   suba.l  (%a5), %a1
| plus the sibling source modes that share the same cracked LOAD -> ALU_SUB
| implementation.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    | Pre-seed an absolute-short source early so the later abs.W load does
    | not sit immediately behind the store in the LSU.
    move.l  #0x00007000, %a6
    move.l  #0x00000009, %d0
    move.l  %d0, (%a6)

    | 1. SUBA.L (A5),A1 -- exact ROM sizing-walker form.
    move.l  #0x00100000, %a5
    move.l  #0x00400000, %d0
    move.l  %d0, (%a5)
    move.l  #0x08000000, %a1
    moveq   #0, %d7
    tst.l   %d7
    suba.l  (%a5), %a1
    bne     _fail1                 | SUBA must preserve CCR
    cmpa.l  #0x07c00000, %a1
    bne     _fail1
    cmpa.l  #0x00100000, %a5
    bne     _fail1

    | 2. SUBA.L (A5)+,A0 and verify postincrement.
    move.l  #0x00100010, %a5
    move.l  #0x00000020, %d0
    move.l  %d0, (%a5)
    move.l  #0x00000100, %a0
    suba.l  (%a5)+, %a0
    cmpa.l  #0x000000e0, %a0
    bne     _fail2
    cmpa.l  #0x00100014, %a5
    bne     _fail2

    | 3. SUBA.L -(A5),A0 and verify predecrement.
    move.l  #0x00100020, %a5
    move.l  #0x00000030, %d0
    move.l  %d0, (%a5)
    move.l  #0x00100024, %a5
    move.l  #0x00000100, %a0
    suba.l  -(%a5), %a0
    cmpa.l  #0x000000d0, %a0
    bne     _fail3
    cmpa.l  #0x00100020, %a5
    bne     _fail3

    | 4. SUBA.L (d16,A5),A0.
    move.l  #0x00100040, %a5
    move.l  #0x00000040, %d0
    move.l  %d0, 16(%a5)
    move.l  #0x00000100, %a0
    suba.l  16(%a5), %a0
    cmpa.l  #0x000000c0, %a0
    bne     _fail4

    | 5. SUBA.L Ay,A0.
    move.l  #0x00000010, %a2
    move.l  #0x00000100, %a0
    suba.l  %a2, %a0
    cmpa.l  #0x000000f0, %a0
    bne     _fail5

    | 6. SUBA.L (xxx).W,A0.
    move.l  #0x00000100, %a0
    suba.l  0x7000.w, %a0
    cmpa.l  #0x000000f7, %a0
    bne     _fail6

    | 7. SUBA.L (xxx).L,A0 from a compile-time data slot.
    move.l  #0x00000100, %a0
    suba.l  _suba_absl_slot, %a0
    cmpa.l  #0x000000e5, %a0
    bne     _fail7

    | 8. SUBA.L (d16,PC),A0.
    move.l  #0x00000100, %a0
    suba.l  _suba_pc_slot(%pc), %a0
    cmpa.l  #0x000000d3, %a0
    bne     _fail8

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

_fail1:
    move.l  #0xDEAD0001, %d2
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d2
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d2
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d2
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d2
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d2
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d2
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d2
    bra     _fail

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail

    .align 2
_suba_pc_slot:
    .long   0x0000002d

    .align 2
_suba_absl_slot:
    .long   0x0000001b
