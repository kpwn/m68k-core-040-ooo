| tstb_block_header.s — TST.B on a memory longword must read the HIGH byte.
| ROM heap scan idiom at 0x4080e14e: TST.B (A1) on a block header whose
| high byte is the tag and low 3 bytes are the size.
    .text
    .org 0
_start:
    lea     0x00080000, %a7
    lea     0x00030000, %a1

    | free block: tag 0x00, size 0x24380 (low byte 0x80, NON-zero)
    move.l  #0x00024380, (%a1)
    tst.b   (%a1)                | high byte 0x00 -> Z must be SET
    bne     _fail1

    | allocated relocatable: tag 0x80, size 0x000100
    move.l  #0x80000100, (%a1)
    tst.b   (%a1)                | high byte 0x80 -> Z clear, N set
    beq     _fail2
    bpl     _fail3

    | allocated nonreloc: tag 0x40, size 0x000404
    move.l  #0x40000404, (%a1)
    tst.b   (%a1)                | high byte 0x40 -> Z clear, N clear
    beq     _fail4
    bmi     _fail5

    | free block whose size low byte is 0x00 too
    move.l  #0x00024300, (%a1)
    tst.b   (%a1)
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt
_fail1: move.l #0xDEAD0001,%d7
    bra _fail
_fail2: move.l #0xDEAD0002,%d7
    bra _fail
_fail3: move.l #0xDEAD0003,%d7
    bra _fail
_fail4: move.l #0xDEAD0004,%d7
    bra _fail
_fail5: move.l #0xDEAD0005,%d7
    bra _fail
_fail6: move.l #0xDEAD0006,%d7
    bra _fail
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_fh: bra _fh
