| rom_boot_postinc_zero_exit.s — MOVE.L (An)+ zero-sentinel scan
|
| Exercises the other common ROM loop shape: postincrement load, BEQ
| on a terminating zero entry, and simple ADDQ bookkeeping on the
| non-zero path. This keeps the branch/decode path honest without
| relying on any RTL changes.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    move.l  #0x00103000, %a0
    move.l  #0x00000001, (%a0)
    move.l  #0x00000002, 4(%a0)
    move.l  #0x00000003, 8(%a0)
    move.l  #0x00000000, 12(%a0)

    moveq   #0, %d0                 | running sum
    moveq   #0, %d1                 | non-zero entry count

_loop:
    move.l  (%a0)+, %d2
    beq     _done
    add.l   %d2, %d0
    addq.l  #1, %d1
    bra     _loop

_done:
    cmp.l   #0x00000006, %d0
    bne     _fail
    cmp.l   #0x00000003, %d1
    bne     _fail
    cmpa.l  #0x00103010, %a0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a1)
_halt_fail:
    bra     _halt_fail
