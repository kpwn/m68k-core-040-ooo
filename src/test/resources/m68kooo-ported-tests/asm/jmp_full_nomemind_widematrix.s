| jmp_full_nomemind_widematrix.s -- JMP full-format extension word,
| I/IS=000 (NO memory indirection) widening.
|
| Real Quadra 700 HW crash bug: ROM 0x4083af36 executes
|   4EF0 2990        JMP (bd,A0,D2.L*1)   [BS=1, IS=0, BD_SIZE=01(null)]
| a completely legal 68020+ full-format EA with NO memory indirection.
| Before this fix, decode_uop_assemble.v's jmp_block forced uop_valid=0
| for ANY full-format extension word (ext1[8]==1) regardless of the I/IS
| sub-field, so this fell all the way through the legacy decoder to a
| vec-4 ILLEGAL trap on real hardware.
|
| This test drives the new jmp_is_fullfmt_nomemind crack through a
| representative matrix of BS / IS / BD_SIZE / scale / index-size / base
| combinations (An-indexed and PC-indexed).  Genuine memory-indirect
| full-format JMP (I/IS!=000) is NOT exercised here -- see the
| pre-existing control_full_memind_siblings.s, which already covers that
| case via the (untouched) legacy decode_0100.vh memind rows.
|
| Sub-cases (each sets a unique bit in D0; final D0 must equal 0x7F):
|   T1 (bit0): BS=1,IS=0,BD=null,  D2.L*1        -- exact HW repro shape.
|   T2 (bit1): BS=1,IS=0,BD=null,  D3.W*2        -- low-memory trampoline
|              (BS=1+null bd caps reach at +-256KiB from zero; verifies
|              Xn.W sign-extend-then-scale still selects ALU_EXT).
|   T3 (bit2): BS=0,IS=0,BD=word,  D4.L*4, An-indexed.
|   T4 (bit3): BS=0,IS=0,BD=long,  D5.W*8, An-indexed.
|   T5 (bit4): BS=0,IS=1,BD=long,  no index,      An-indexed.
|   T6 (bit5): BS=1,IS=1,BD=word,  no index, both suppressed (hand-coded
|              -- gas always prefers a shorter existing mode for this
|              shape) -- low-memory trampoline (same reach cap as T2).
|   T7 (bit6): BS=0,IS=0,BD=word,  D6.W*1, PC-indexed.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.  Any wrong landing / wrong
| final checksum writes a distinct 0xDEADxxxx marker instead.

    .text
    .org 0

_start:
    moveq   #0, %d0

    | ---- T1: BS=1, IS=0, BD=null, D2.L*1 (exact HW repro shape) ----
    move.l  #_t1_land, %d2
    jmp     (%d2.l*1)
    bra     _fail_t1
_t1_land:
    or.l    #0x00000001, %d0

    | ---- T2: BS=1, IS=0, BD=null, D3.W*2 (low-memory trampoline) ----
    | EA = sign_extend(D3.w) * 2.  Reach is capped at +-64KiB*scale from
    | zero, nowhere near this program's load address -- write a tiny
    | JMP (xxx).L trampoline into low memory and land there instead.
    lea     0x00002000, %a0
    move.w  #0x4ef9, (%a0)          | JMP (xxx).L opcode
    move.l  #_t2_after, 2(%a0)      | absolute target operand
    move.l  #0x00001000, %d3        | 0x1000 * 2 = 0x2000
    jmp     (%d3.w*2)
    bra     _fail_t2
_t2_after:
    or.l    #0x00000002, %d0

    | ---- T3: BS=0, IS=0, BD=word, D4.L*4, An-indexed ----
    move.l  #0x00000010, %d4        | contributes 0x10*4 = 0x40
    lea     ((_t3_land - 0x1000) - 0x40), %a3
    jmp     (0x1000,%a3,%d4.l*4)
    bra     _fail_t3
_t3_land:
    or.l    #0x00000004, %d0

    | ---- T4: BS=0, IS=0, BD=long, D5.W*8, An-indexed ----
    move.l  #0x00000010, %d5        | contributes 0x10*8 = 0x80
    lea     ((_t4_land - 0x123456) - 0x80), %a4
    jmp     (0x123456,%a4,%d5.w*8)
    bra     _fail_t4
_t4_land:
    or.l    #0x00000008, %d0

    | ---- T5: BS=0, IS=1, BD=long, no index, An-indexed ----
    lea     (_t5_land - 0x654321), %a2
    jmp     (0x654321,%a2)
    bra     _fail_t5
_t5_land:
    or.l    #0x00000010, %d0

    | ---- T6: BS=1, IS=1, BD=word, no index, both suppressed ----
    | EA = sign_extend(bd) only.  Same +-32KiB reach cap as T2/null-bd
    | cases -- trampoline into low memory.
    lea     0x00002100, %a0
    move.w  #0x4ef9, (%a0)
    move.l  #_t6_after, 2(%a0)
    .word   0x4ef0, 0x01e0, 0x2100  | JMP full-fmt BS=1,IS=1,BD=word(0x2100)
    bra     _fail_t6
_t6_after:
    or.l    #0x00000020, %d0

    | ---- T7: BS=0, IS=0, BD=word, D6.W*1, PC-indexed ----
    moveq   #0, %d6
    jmp     (_t7_land,%pc,%d6.w*1)
    bra     _fail_t7
    .space  200                      | force word-size bd (>127 away)
_t7_land:
    or.l    #0x00000040, %d0

    | ---- Final checksum ----
    cmp.l   #0x0000007F, %d0
    bne     _fail_checksum

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail_t1:
    move.l  #0xDEAD0001, %d7
    bra     _write_fail
_fail_t2:
    move.l  #0xDEAD0002, %d7
    bra     _write_fail
_fail_t3:
    move.l  #0xDEAD0003, %d7
    bra     _write_fail
_fail_t4:
    move.l  #0xDEAD0004, %d7
    bra     _write_fail
_fail_t5:
    move.l  #0xDEAD0005, %d7
    bra     _write_fail
_fail_t6:
    move.l  #0xDEAD0006, %d7
    bra     _write_fail
_fail_t7:
    move.l  #0xDEAD0007, %d7
    bra     _write_fail
_fail_checksum:
    move.l  #0xDEADBEEF, %d7

_write_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
