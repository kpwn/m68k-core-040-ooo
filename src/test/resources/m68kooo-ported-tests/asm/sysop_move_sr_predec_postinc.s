| sysop_move_sr_predec_postinc.s — directed test for MOVE.W SR with
| predecrement / postincrement EAs.  Companion to the existing Dn /
| (An) variants in the v2_sysop suite.
|
| Pre-fix: MOVE.W SR,-(An) and MOVE.W SR,(An)+ were silently NOP'd
| in decode_uop_assemble.v's sem_sysop_is_move_sr_ea_src branch (only
| Dn-direct mode 000 and (An) mode 010 were assembled; everything
| else fell through to SYS_NOP with len_bytes for ext words).  This
| caused bsr_rts_odd_sp.s to mis-locate stack contents (since SP
| didn't decrement for the SR push) and would silently corrupt any
| supervisor code path doing `MOVE SR,-(SP)` to save status.
|
| Post-fix: full predec/postinc support, mirroring the (An) crack
| with a leading SUB or trailing ADD for the An writeback.

    .text
    .org 0
    .equ FAIL_ADDR, 0xFFFF0000
    .equ PASS_VAL,  0xC0FFEE00

_start:
    | --- Test 1: MOVE.W SR, -(An) ---
    | Pre-place sentinel; verify (a) An decremented by 2, (b) SR low
    | word stored at the new An.  Snapshot SR AFTER the move.l/move.l
    | setup so the snapshot reflects the live CCR (SR low byte ≡ CCR;
    | the setup move.l ops updated CCR via the rename network, so a
    | snapshot taken BEFORE the setup would mismatch the SR value
    | actually pushed by `MOVE.W SR,-(An)`).
    move.l  #0xDEADBEEF, 0x00120000
    move.l  #0x00120004, %a1
    move.w  %sr, %d1                    | snapshot SR (live CCR included)
    move.w  %sr, -(%a1)
    | A1 should now be 0x00120002.
    cmp.l   #0x00120002, %a1
    bne     _fail_t1_an
    | Read back the stored SR low word: aligned word read at 0x00120002
    | should give the SR value.  Compare to MOVE.W SR,Dn snapshot.
    move.w  0x00120002, %d2
    cmp.w   %d1, %d2
    bne     _fail_t1_val

    | --- Test 2: MOVE.W SR, (An)+ ---
    move.l  #0x00120010, %a2
    move.l  %a2, %d3                    | save original (also updates CCR)
    move.w  %sr, %d1                    | re-snapshot SR after setup
    move.w  %sr, (%a2)+
    | A2 should now be 0x00120012.
    cmp.l   #0x00120012, %a2
    bne     _fail_t2_an
    | Read back stored SR low word at original A2.
    move.w  0x00120010, %d4
    cmp.w   %d1, %d4                    | compare against MOVE.W SR,D1 snapshot
    bne     _fail_t2_val

    | --- Test 3: MOVE.W SR, -(SP) at ALIGNED SP — the common case ---
    move.l  #0x00200000, %a7
    move.w  %sr, -(%a7)
    cmp.l   #0x001FFFFE, %a7
    bne     _fail_t3_an

    | --- Test 4: MOVE.W SR, -(SP) at ODD SP (EA off=3 → split-word store) ---
    | A7 odd → after predec by 2 still odd; word store at off=3 splits.
    move.l  #0x000FFFFF, %a7
    move.w  %sr, %d1                    | re-snapshot SR after the setup
    move.w  %sr, -(%a7)
    cmp.l   #0x000FFFFD, %a7
    bne     _fail_t4_an
    | Read back: split word load at A7=0x000FFFFD (off=1) — also tests
    | the split-load path.
    move.w  (%a7), %d5
    cmp.w   %d1, %d5
    bne     _fail_t4_val

    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  #PASS_VAL, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail_t1_an:
    move.l  %a1, %d7
    bra     _fail
_fail_t1_val:
    move.l  #0xDEAD0011, %d7
    bra     _fail
_fail_t2_an:
    move.l  %a2, %d7
    bra     _fail
_fail_t2_val:
    move.l  #0xDEAD0021, %d7
    bra     _fail
_fail_t3_an:
    move.l  %a7, %d7
    move.l  #0x00200000, %a7
    bra     _fail
_fail_t4_an:
    move.l  %a7, %d7
    move.l  #0x00200000, %a7
    bra     _fail
_fail_t4_val:
    move.l  #0xDEAD0041, %d7
    bra     _fail
_fail:
    move.l  #0x00200000, %a7
    lea     FAIL_ADDR, %a0
    move.l  %d7, (%a0)
1:  bra     1b
