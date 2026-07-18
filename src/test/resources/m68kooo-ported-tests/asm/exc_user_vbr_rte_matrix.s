| exc_user_vbr_rte_matrix.s - relocated VBR + user exception RTE matrix
|
| Compose the boot-critical exception pieces that tended to pass as
| isolated tests but fail when combined:
|   - relocated VBR dispatch for TRAP #0, A-line, F-line, ILLEGAL, TRAP #15
|   - user-mode exception entry switches from USP to SSP
|   - format-0 frame carries the correct vector-table byte offset
|   - RTE restores user A7 and retries at the adjusted stacked PC
|
| PASS: all five handlers run, each RTE returns to user mode with USP intact.

    .text
    .org 0

_start:
    lea     0x00020000, %a7              | SSP base while supervisor

    move.l  #0x00010000, %d0
    movec   %d0, %vbr
    movec   %vbr, %d1
    cmp.l   %d0, %d1
    bne     _fail_vbr

    move.l  #_h_illegal, 0x00010010     | vec 4
    move.l  #_h_aline,   0x00010028     | vec 10
    move.l  #_h_fline,   0x0001002C     | vec 11
    move.l  #_h_trap0,   0x00010080     | vec 32
    move.l  #_h_trap15,  0x000100BC     | vec 47

    moveq   #0, %d7

    | Drop to user mode.  A7 becomes USP; establish a user stack.
    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7

    trap    #0
    cmp.l   #0x00008000, %a7
    bne     _fail_usp

    .short  0xA123
    cmp.l   #0x00008000, %a7
    bne     _fail_usp

    .short  0xF123
    cmp.l   #0x00008000, %a7
    bne     _fail_usp

    .short  0x4AFC
    cmp.l   #0x00008000, %a7
    bne     _fail_usp

    trap    #15
    cmp.l   #0x00008000, %a7
    bne     _fail_usp

    cmp.l   #0x0000001F, %d7
    bne     _fail_count

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_h_trap0:
    cmp.l   #0x0001FFF8, %a7
    bne     _fail_ssp
    move.l  4(%a7), %d0
    and.l   #0x00000FFF, %d0
    cmp.l   #0x00000080, %d0
    bne     _fail_vec
    or.l    #0x00000001, %d7
    rte

_h_aline:
    | A-line uses 68040 format 0 (8 bytes) per PRM §8.4.1.  Saved-PC
    | points to the A-line opcode.  SSP after push = 0x1FFFF8 (top
    | 0x20000 minus 8).  The Q700 ROM dispatcher relies on the
    | 8-byte size: it overlays the saved PC's low long with the
    | advanced PC and uses `addqw #4, sp; rts` to pop exactly that
    | overlay.  An fmt-2 frame would leak 4 bytes, RTS would pop the
    | wrong PC, and the next A-trap would re-fault at the same
    | A-line — infinite loop.
    cmp.l   #0x0001FFF8, %a7
    bne     _fail_ssp
    move.l  4(%a7), %d0
    and.l   #0x00000FFF, %d0
    cmp.l   #0x00000028, %d0
    bne     _fail_vec
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    or.l    #0x00000002, %d7
    rte

_h_fline:
    | F-line: 68040 format 2 frame (12 bytes), SSP = 0x1FFF4.
    cmp.l   #0x0001FFF4, %a7
    bne     _fail_ssp
    move.l  4(%a7), %d0
    and.l   #0x00000FFF, %d0
    cmp.l   #0x0000002C, %d0
    bne     _fail_vec
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    or.l    #0x00000004, %d7
    rte

_h_illegal:
    cmp.l   #0x0001FFF8, %a7
    bne     _fail_ssp
    move.l  4(%a7), %d0
    and.l   #0x00000FFF, %d0
    cmp.l   #0x00000010, %d0
    bne     _fail_vec
    move.l  2(%a7), %d0
    addq.l  #2, %d0
    move.l  %d0, 2(%a7)
    or.l    #0x00000008, %d7
    rte

_h_trap15:
    cmp.l   #0x0001FFF8, %a7
    bne     _fail_ssp
    move.l  4(%a7), %d0
    and.l   #0x00000FFF, %d0
    cmp.l   #0x000000BC, %d0
    bne     _fail_vec
    or.l    #0x00000010, %d7
    rte

_fail_vbr:
    move.l  #0xDEAD0001, %d0
    bra     _write_fail
_fail_usp:
    move.l  #0xDEAD0002, %d0
    bra     _write_fail
_fail_ssp:
    move.l  #0xDEAD0003, %d0
    bra     _write_fail
_fail_vec:
    move.l  #0xDEAD0004, %d0
    bra     _write_fail
_fail_count:
    move.l  #0xDEAD0005, %d0
    bra     _write_fail

_write_fail:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail
