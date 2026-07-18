| movem_postinc_an_in_list.s -- MOVEM postincrement with the base An in
| the destination register list.
|
| CORRECTED 2026-07-16 (v2-fire-gate-audit merge): the original version
| of this test asserted that the LOADED value stays in An ("PRM: loaded
| value remains, postinc must not clobber it").  Direct Musashi replay
| (tools/fuzz/fuzz.py --replay against build/tests/movem_postinc_an_in_
| list.bin) proves that reading is WRONG for this addressing mode.
| Musashi's movem_32_er_pi (tb/models/musashi/m68k_in.c) keeps a LOCAL
| `ea` cursor, loads registers (including An itself, if listed) from
| it in mask order, then unconditionally does `AY = ea;` AFTER the
| loop -- so the postincrement ALWAYS wins and the loaded value in An
| is discarded, regardless of whether An was in the list.  (This is
| asymmetric with the -(An) predecrement STORE case, which genuinely
| does preserve the ORIGINAL An -- there REG_DA is never written
| during the loop, so reading An mid-loop naturally sees the pre-decrement
| value; see movem_an_in_list.s.)  The RTL (decode_uop_assemble.v's
| movem_ld_prologue: An snapshotted to TMP1, all loads/writeback base
| off TMP1) matches this Musashi ground truth: the postinc writeback
| always executes and always wins.
|
| Expected per Musashi: a0 = orig_a0 + 2*4 = 0x00030008 (postinc wins,
| loaded 0x00123456 is discarded); a1 = 0x00ABCDEF (loaded value stands
| -- a1 is not the base register, so it's never touched again).
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL: 0xDEADBEEF at 0xFFFF0000.

    .text
    .org 0

_start:
    lea     0x00030000, %a0
    move.l  #0x00123456, (%a0)
    move.l  #0x00ABCDEF, 4(%a0)

    movem.l (%a0)+, %a0-%a1

    move.l  %a0, %d0
    cmp.l   #0x00030008, %d0
    bne     _fail
    move.l  %a1, %d0
    cmp.l   #0x00ABCDEF, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a2)
_halt_fail:
    bra     _halt_fail
