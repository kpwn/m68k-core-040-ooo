| bsr_unaligned_low_stack.s -- BSR push when A7 is not long-aligned
|
| ROM frontier covered:
|   The Q700 ROM currently reaches a BSR with A7 around 0x0000fece,
|   which pushes its return PC to 0x0000feca.  That split LONG store is
|   later consumed by an RTS.  The pushed return address must remain the
|   high-ROM fall-through PC, not a low alias or stale stack value.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    movea.l #0x0000fece, %a7
    bsr     _sub
_after_bsr:
    cmpa.l  #0x0000fece, %a7
    bne     _fail

    | BSR from A7=0x...fece pushes to 0x...feca, a split LONG store.
    movea.l #0x0000feca, %a0
    move.l  (%a0), %d0
    cmp.l   #_after_bsr, %d0
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_sub:
    move.l  (%a7), %d1
    cmp.l   #_after_bsr, %d1
    bne     _fail
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
