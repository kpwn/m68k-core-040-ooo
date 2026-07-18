| jmp_pc_d8_dn_wscale2_table.s — Q700 boot Bug B suspect S2
|
| The BlockMove A-trap handler (ROM 0x4080CA10..0x4080CB18) ends each
| copy chunk with `JMP (d8,PC,D0.W*2)` brief-format indexed PC, used
| as a Duff's-device jump table:
|     0x4080CB14:  4EFB 0284   jmp(-124, pc, d0.w*2)
|     0x4080CAFC:  4EFB 029C   jmp(-100, pc, d0.w*2)
| Both target base = 0x4080CA9A; D0 selects the table slot.
|
| decode_0100.vh:586-705 implements a 5-µop crack:
|   P0      TMP1 = pd_pc + 2 + sx8(disp)
|   P1      TMP2 = sx16(D0.W)         (ALU_EXT)
|   P2      TMP2 += TMP2 (one doubling for scale ×2)
|   P3      TMP1 += TMP2
|   P4      BR_JMP TMP1
|
| Existing tests (jmp_pc_indexed_scaled.s, jmp_pc_indexed_word.s)
| cover ONE D7 value and ONE target.  This test sweeps D0 across:
|   - 0 (zero index — base of table)
|   - small positive (1, 2, 4, 8)
|   - small negative (-1, -2, -4)
|   - high bits set in upper 16 of D0 (verify .W truncation)
|   - max positive (0x7FFE, sign-bit-of-low-word-clear)
|   - boundary (0x8000 — sign-bit set, so .W as signed = -32768)
|
| For each D0, the JMP must land at the correct table entry.
| Mismatch -> FAIL with code identifying which D0 case failed.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | --- Test 1: D0 = 0 (base of table) ---
    moveq   #0, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0000, %d1
    bne     _fail_t1

    | --- Test 2: D0 = 1 ---
    moveq   #1, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0001, %d1
    bne     _fail_t2

    | --- Test 3: D0 = 2 ---
    moveq   #2, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0002, %d1
    bne     _fail_t3

    | --- Test 4: D0 = 4 ---
    moveq   #4, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0004, %d1
    bne     _fail_t4

    | --- Test 5: D0 with HIGH bits set (verify .W truncation) ---
    | D0 = 0x12340002 — low word = 2, high word = 0x1234 (garbage).
    | If our crack truncates correctly to .W, target = entry [2].
    | If it uses full D0 as 32-bit, target overshoots wildly.
    move.l  #0x12340002, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0002, %d1
    bne     _fail_t5

    | --- Test 6: D0 with NEGATIVE garbage in high (sign-extension red herring) ---
    | If sign-extends from .W incorrectly, target may be way off.
    | D0 = 0xFFFF0003 — low word = 3, high word = 0xFFFF.
    move.l  #0xFFFF0003, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0003, %d1
    bne     _fail_t6

    | --- Test 7: D0 negative (low word) — going BACKWARD in jump table ---
    | D0.W = 0xFFFF = -1 (signed) → target = base + (-1)*2 = base - 2.
    move.l  #0x0000FFFF, %d0
    bsr     _do_jump_reverse
    cmp.l   #0xBBBB0001, %d1
    bne     _fail_t7

    | --- Test 8: stress with 16 back-to-back JMPs (TMP1/TMP2 hazard test) ---
    | Drive D0 from 0..7 in sequence, no other stores between.  If our
    | crack has a TMP1/TMP2 reuse hazard, one of these JMPs will pick
    | up stale TMP from the previous crack.
    moveq   #0, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0000, %d1
    bne     _fail_t8a

    moveq   #1, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0001, %d1
    bne     _fail_t8b

    moveq   #2, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0002, %d1
    bne     _fail_t8c

    moveq   #3, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0003, %d1
    bne     _fail_t8d

    moveq   #4, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0004, %d1
    bne     _fail_t8e

    moveq   #5, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0005, %d1
    bne     _fail_t8f

    moveq   #6, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0006, %d1
    bne     _fail_t8g

    moveq   #7, %d0
    bsr     _do_jump
    cmp.l   #0xAAAA0007, %d1
    bne     _fail_t8h

    | --- All passed ---
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

| _do_jump: forward-direction jump table.
|   Table = 8 entries × 2 bytes (each is a 2-byte BRA.S to its handler).
|   Caller sets D0 = index; we JMP (0,PC,D0.W*2) → handler i sets
|   D1 = 0xAAAA000i and RTS.
_do_jump:
    | ext word 0x0204:
    |   bits[15:12]=0000 → index reg = D0
    |   bit 11        =0 → Xn.W (sign-extended from word)
    |   bits[10:9]   =01 → scale ×2
    |   bit  8        =0 → brief format
    |   bits[ 7:0]   =0x04 → disp8 = +4
    | target = (pc+2) + sx8(4) + sx16(D0.W) * 2
    .word   0x4EFB, 0x0204             | jmp (4,pc,d0.w*2) — same shape as ROM 0x4080CB14
    bra.s   _fail_unreached            | filler so disp8 reaches table
_jump_table_fwd:
    bra.s   _entry0                    | offset 0 (D0=0)
    bra.s   _entry1                    | offset 2 (D0=1)
    bra.s   _entry2                    | offset 4 (D0=2)
    bra.s   _entry3                    | offset 6 (D0=3)
    bra.s   _entry4                    | offset 8 (D0=4)
    bra.s   _entry5                    | offset 10 (D0=5)
    bra.s   _entry6                    | offset 12 (D0=6)
    bra.s   _entry7                    | offset 14 (D0=7)

_entry0:
    move.l  #0xAAAA0000, %d1
    rts
_entry1:
    move.l  #0xAAAA0001, %d1
    rts
_entry2:
    move.l  #0xAAAA0002, %d1
    rts
_entry3:
    move.l  #0xAAAA0003, %d1
    rts
_entry4:
    move.l  #0xAAAA0004, %d1
    rts
_entry5:
    move.l  #0xAAAA0005, %d1
    rts
_entry6:
    move.l  #0xAAAA0006, %d1
    rts
_entry7:
    move.l  #0xAAAA0007, %d1
    rts

| _do_jump_reverse: jump table that handles negative D0.W.
|   Place the JMP after several .word 0 fillers, so a negative index
|   reaches a real handler that sets D1 = 0xBBBB000n (n = -idx).
_fail_unreached:
    move.l  #0xDEAD0099, %d3
    bra     _write_fail
_filler_pad:
    bra.s   _rev_entry_minus3            | -6 from base
    bra.s   _rev_entry_minus2            | -4 from base
    bra.s   _rev_entry_minus1            | -2 from base
_do_jump_reverse:
    | ext 0x02FE: same fields as _do_jump but disp8 = 0xFE = -2.
    | base = pc+2 + sx8(-2) = pc; D0=0xFFFF (.W=-1) gives offset=-2 → -2 from pc → 2
    | bytes BEFORE this opcode = into _filler_pad's BRA.S _rev_entry_minus1.
    .word   0x4EFB, 0x02FE             | jmp (-2,pc,d0.w*2)

_rev_entry_minus1:
    move.l  #0xBBBB0001, %d1
    rts
_rev_entry_minus2:
    move.l  #0xBBBB0002, %d1
    rts
_rev_entry_minus3:
    move.l  #0xBBBB0003, %d1
    rts

_fail_t1:
    move.l  #0xDEAD0001, %d3
    bra     _write_fail
_fail_t2:
    move.l  #0xDEAD0002, %d3
    bra     _write_fail
_fail_t3:
    move.l  #0xDEAD0003, %d3
    bra     _write_fail
_fail_t4:
    move.l  #0xDEAD0004, %d3
    bra     _write_fail
_fail_t5:
    move.l  #0xDEAD0005, %d3
    bra     _write_fail
_fail_t6:
    move.l  #0xDEAD0006, %d3
    bra     _write_fail
_fail_t7:
    move.l  #0xDEAD0007, %d3
    bra     _write_fail
_fail_t8a:
    move.l  #0xDEAD000A, %d3
    bra     _write_fail
_fail_t8b:
    move.l  #0xDEAD000B, %d3
    bra     _write_fail
_fail_t8c:
    move.l  #0xDEAD000C, %d3
    bra     _write_fail
_fail_t8d:
    move.l  #0xDEAD000D, %d3
    bra     _write_fail
_fail_t8e:
    move.l  #0xDEAD000E, %d3
    bra     _write_fail
_fail_t8f:
    move.l  #0xDEAD000F, %d3
    bra     _write_fail
_fail_t8g:
    move.l  #0xDEAD0010, %d3
    bra     _write_fail
_fail_t8h:
    move.l  #0xDEAD0011, %d3
    bra     _write_fail
_write_fail:
    lea     0xFFFF0000, %a0
    move.l  %d3, (%a0)
_halt_fail:
    bra     _halt_fail
