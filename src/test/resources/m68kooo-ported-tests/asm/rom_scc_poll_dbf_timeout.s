| rom_scc_poll_dbf_timeout.s -- ROM SCC poll timeout loop shape.
|
| Mirrors the Q700 ROM loop at 0x408478b4:
|     btst #0,(a3)
|     bne  done
|     dbf  d4,loop
|
| On hardware bring-up the FPGA reached this loop with the SCC status bit
| clear (matching MAME) but then stopped retiring near the DBF.  This test
| keeps the polled byte in RAM so any hang is in core branch/retire state,
| not in the SCC peripheral model.

    .text
    .org 0

_start:
    lea     0x00020000, %a7
    lea     _status_byte, %a3
    move.w  #0xffff, %d4
    moveq   #0, %d6

_loop:
    btst    #0, (%a3)
    bne     _done
    dbf     %d4, _loop
    moveq   #3, %d6

_done:
    cmpi.l  #3, %d6
    bne     _fail
    cmpi.l  #0x0000ffff, %d4
    bne     _fail

_pass:
    lea     0xffff0000, %a0
    move.l  #0xc0ffee00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xffff0000, %a0
    move.l  #0xbad478b4, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

    .data
    .align 2
_status_byte:
    .byte   0x44
