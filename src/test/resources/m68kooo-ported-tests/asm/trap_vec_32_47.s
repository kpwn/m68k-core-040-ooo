| trap_vec_32_47.s — TRAP #0, #5, #15 dispatch via VBR (Stage D-7)
|
| Verifies V2 assembles TRAP #n to UOP_SYS with exc_valid=1 and
| exc_vec = 32 + n.  The exception sequencer dispatches to
| mem[VBR + (32+n)*4], which is 0x00000080 / 0x00000094 / 0x000000BC
| with VBR=0.
|
| Each chained handler bumps a counter in D7 and returns via RTE to the
| next TRAP.  After TRAP #15 completes D7 must equal 3, at which point
| the PASS sentinel fires.  A handler mismatch or missed dispatch falls
| through to FAIL.
|
| PASS: after the three TRAPs, mem[0xFFFF0000] == 0xC0FFEE00.
| FAIL: any handler does not run, or fallthrough writes DEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    moveq   #0, %d7

    | Install handlers at the correct VBR offsets (VBR=0 on reset).
    move.l  #_h0,  0x00000080        | vec 32
    move.l  #_h5,  0x00000094        | vec 37
    move.l  #_h15, 0x000000BC        | vec 47

    trap    #0
    trap    #5
    trap    #15

    | All three handlers must have run.  Each handler adds 1 to D7.
    cmp.l   #3, %d7
    bne     _fail

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

_h0:
    addq.l  #1, %d7
    rte
_h5:
    addq.l  #1, %d7
    rte
_h15:
    addq.l  #1, %d7
    rte
