| jsr_full_nomemind_widematrix.s -- JSR full-format extension word,
| I/IS=000 (NO memory indirection) widening -- JSR sibling of
| jmp_full_nomemind_widematrix.s.
|
| Same underlying bug class as the JMP HW crash (ROM 0x4083af36): the
| assembler forced uop_valid=0 for ANY full-format JSR extension word
| (ext1[8]==1) regardless of the I/IS sub-field, so no-memind full-format
| JSR fell through to legacy and (depending on the exact EA) could trap
| vec-4 ILLEGAL instead of executing.  This test drives the new
| jsr_is_fullfmt_nomemind crack and additionally checks return-address /
| stack-pointer balance across the call+RTS pair -- errors in the new
| len_bytes computation would corrupt the pushed return PC and desync A7,
| which JSR/JMP don't otherwise self-check.
|
| Genuine memory-indirect full-format JSR (I/IS!=000) is NOT exercised
| here -- see the pre-existing jsr_preindexed_memind_atrap_table.s and
| control_full_memind_siblings.s, which already cover that case via the
| (untouched) legacy decode_0100.vh memind rows.
|
| Sub-cases (each sets a unique bit in D0; final D0 must equal 0x0F):
|   J1 (bit0): BS=1,IS=0,BD=null, D2.L*1        -- exact HW-bug shape.
|   J2 (bit1): BS=0,IS=0,BD=word, D4.L*4, An-indexed.
|   J3 (bit2): BS=0,IS=1,BD=long, no index,     An-indexed.
|   J4 (bit3): BS=0,IS=0,BD=word, D6.W*1,       PC-indexed.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    moveq   #0, %d0

    | ---- J1: BS=1, IS=0, BD=null, D2.L*1 ----
    move.l  %a7, %d1                | pre-call SP snapshot
    move.l  #_j1_sub, %d2
    jsr     (%d2.l*1)
    cmpa.l  %d1, %a7                | SP must be restored after RTS
    bne     _fail_j1
    or.l    #0x00000001, %d0

    | ---- J2: BS=0, IS=0, BD=word, D4.L*4, An-indexed ----
    move.l  %a7, %d1
    move.l  #0x00000010, %d4        | contributes 0x10*4 = 0x40
    lea     ((_j2_sub - 0x1000) - 0x40), %a3
    jsr     (0x1000,%a3,%d4.l*4)
    cmpa.l  %d1, %a7
    bne     _fail_j2
    or.l    #0x00000002, %d0

    | ---- J3: BS=0, IS=1, BD=long, no index, An-indexed ----
    move.l  %a7, %d1
    lea     (_j3_sub - 0x654321), %a2
    jsr     (0x654321,%a2)
    cmpa.l  %d1, %a7
    bne     _fail_j3
    or.l    #0x00000004, %d0

    | ---- J4: BS=0, IS=0, BD=word, D6.W*1, PC-indexed ----
    move.l  %a7, %d1
    moveq   #0, %d6
    jsr     (_j4_sub,%pc,%d6.w*1)
    cmpa.l  %d1, %a7
    bne     _fail_j4
    or.l    #0x00000008, %d0

    | ---- Final checksum ----
    cmp.l   #0x0000000F, %d0
    bne     _fail_checksum

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_j1_sub:
    rts

_j2_sub:
    rts

_j3_sub:
    rts

    .space  200                      | force word-size bd for J4 (>127 away)
_j4_sub:
    rts

_fail_j1:
    move.l  #0xDEAD0011, %d7
    bra     _write_fail
_fail_j2:
    move.l  #0xDEAD0012, %d7
    bra     _write_fail
_fail_j3:
    move.l  #0xDEAD0013, %d7
    bra     _write_fail
_fail_j4:
    move.l  #0xDEAD0014, %d7
    bra     _write_fail
_fail_checksum:
    move.l  #0xDEADBEEF, %d7

_write_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
