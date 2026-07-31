| exc_aline_after_sr_byte_postinc_odd_sp.s -- A-line after SR/byte SP pops
|
| Mirrors the low-RAM boot sequence at 0x6b50:
|   move.w  (sp)+,sr
|   move.b  (sp)+,d0
|   .short  0xa05d
|
| The byte postincrement leaves A7 odd immediately before exception entry.
| PASS requires the vec-10 format-0 frame to use that fresh odd A7,
| not a stale supervisor stack shadow.

    .text
    .org 0

_start:
    move.l  #_handler, 0x00000028

    lea     0x00010001, %a7
    move.w  #0x2004, (%a7)         | SR to restore
    move.b  #0x7e, 2(%a7)          | byte popped into D0
    move.l  #0x11223344, %d0

    move.w  (%a7)+, %sr
    move.b  (%a7)+, %d0            | A7 = 0x10005 (odd, freshly renamed)
_aline_site:
    .short  0xa05d

_fallthrough:
    move.l  #0xDEAD0000, %d7
    bra     _fail

_handler:
    | Check the exception used the fresh post-byte-pop A7.
    cmp.l   #0x0000fffd, %a7       | 0x10005 - 8-byte format-0 frame
    bne     _fail_sp

    move.l  %a2, -(%sp)
    move.l  %d2, -(%sp)
    movea.l 10(%sp), %a2
    cmp.l   #_aline_site, %a2
    bne     _fail_pc

    move.w  (%a2)+, %d2
    cmp.w   #0xa05d, %d2
    bne     _fail_opword

    cmpi.w  #0xa800, %d2
    bcs     _pass

_fail_cmp_branch:
    move.l  #0xDEAD0003, %d7
    bra     _fail

_fail_sp:
    move.l  #0xDEAD0004, %d7
    bra     _fail

_fail_pc:
    move.l  #0xDEAD0001, %d7
    bra     _fail

_fail_opword:
    move.l  #0xDEAD0002, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
