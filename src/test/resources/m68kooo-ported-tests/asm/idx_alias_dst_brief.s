| idx_alias_dst_brief.s — brief-format (d8,An,Xn.SIZE*SCALE) effective
| CYCLE BUDGET: this test carries a `.timeout` sidecar (3,000,000 cycles).
| It is not slow because anything is wrong -- it front-loads a large
| poison-fill / setup loop before the first assertion, and simply does not
| fit the 200,000-cycle default. Without the sidecar it reports HANG, which
| reads exactly like a core deadlock and cost real investigation time on
| 2026-09-09. Verified: it PASSES with the larger budget, unchanged.
|
| addresses where the INDEX register and the DESTINATION register are
| THE SAME register.
|
| Hazard class: in an OoO core the index must be read from the OLD
| physical register while the destination allocates a NEW one.  The
| project has already shipped one same-register operand-ordering bug in
| this family (predecrement, commit faf901ab), so this file fences the
| indexed-EA analogue.
|
| Musashi golden (m68k_in.c, move <ea>,Dn):
|     res = OPER_AY_IX_*()   -- EA built from the PRE-instruction Xn
|     DX  = merge(res)       -- destination written afterwards
|
| The whole SCRATCH window is pre-filled with 0xEE so that ANY wrong
| effective address reads a value that cannot be mistaken for a correct
| one (and a wildly wrong EA reads 0x00 from unwritten RAM).
|
| Covers: operand size B/W/L, index size .W and .L, scales 1/2/4/8,
| word-index sign extension (negative index), and the exact shape of the
| ROM instruction that motivated this audit
| (`moveq #1,D1 ; move.b ($78,A2,D1.w),D1`).

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Pre-fill 0x2000 bytes of SCRATCH with 0xEE poison ──
    lea     SCRATCH, %a0
    move.w  #0x1FFF, %d0
_fill:
    move.b  #0xEE, (%a0)+
    dbf     %d0, _fill

    | ── Part 0: the ROM shape — moveq #1,D1 ; move.b ($78,A2,D1.w),D1 ─
    | A2 = SCRATCH+0x0000.  byte[0x78] = 0x07 (index-0 decoy),
    | byte[0x79] = 0x0A (correct), byte[0x82] = 0x5C (post-load-index
    | decoy: 0x78 + loaded 0x0A = 0x82).
    lea     SCRATCH, %a2
    move.b  #0x07, (0x78,%a2)
    move.b  #0x0A, (0x79,%a2)
    move.b  #0x5C, (0x82,%a2)
    moveq   #1, %d1
    move.b  (0x78,%a2,%d1.w), %d1
    cmpi.b  #0x0A, %d1
    bne     _fail0
    cmpi.l  #0x0000000A, %d1        | moveq left D1 = 1, byte merge -> 0x0A
    bne     _fail1

    | ── Part 1: byte operand, .W index, dirty upper bits in the index ──
    | D1 = 0x11220001 -> index .w = 1, upper 16 bits MUST be ignored.
    | A2 = SCRATCH+0x100; byte[0x100+0x78+1] = 0x3C.
    | Result must be 0x1122003C (byte merge into the old D1).
    lea     SCRATCH+0x100, %a2
    move.b  #0x3C, (0x79,%a2)
    move.l  #0x11220001, %d1
    move.b  (0x78,%a2,%d1.w), %d1
    cmp.l   #0x1122003C, %d1
    bne     _fail2

    | ── Part 2: word operand, .W index ──
    | D2 = 0x77770002; A2 = SCRATCH+0x200; word[0x200+0x10+2] = 0xBEEF.
    lea     SCRATCH+0x200, %a2
    move.w  #0xBEEF, (0x12,%a2)
    move.l  #0x77770002, %d2
    move.w  (0x10,%a2,%d2.w), %d2
    cmp.l   #0x7777BEEF, %d2
    bne     _fail3

    | ── Part 3: long operand, .L index ──
    | D3 = 0x00000004; A2 = SCRATCH+0x300; long[0x304] = 0xDEADBEEF.
    lea     SCRATCH+0x300, %a2
    move.l  #0xDEADBEEF, (4,%a2)
    moveq   #4, %d3
    move.l  (0,%a2,%d3.l), %d3
    cmp.l   #0xDEADBEEF, %d3
    bne     _fail4

    | ── Part 4: NEGATIVE .W index — sign extension to 32 bits ──
    | D4 = 0x0001FFFE (low word = -2, upper word garbage that must be
    | dropped); A2 = SCRATCH+0x400; long[0x400+0x10-2] = 0xCAFEBABE.
    | A zero-extending core would address SCRATCH+0x1040E instead.
    lea     SCRATCH+0x400, %a2
    move.l  #0xCAFEBABE, (0x0E,%a2)
    move.l  #0x0001FFFE, %d4
    move.l  (0x10,%a2,%d4.w), %d4
    cmp.l   #0xCAFEBABE, %d4
    bne     _fail5

    | ── Part 5: scale x1 (byte) ──
    | A2 = SCRATCH+0x500; byte[0x505] = 0x33; D5 = 5.
    lea     SCRATCH+0x500, %a2
    move.b  #0x33, (5,%a2)
    moveq   #5, %d5
    move.b  (0,%a2,%d5.w), %d5
    cmp.l   #0x00000033, %d5
    bne     _fail6

    | ── Part 6: scale x2 (word) ── D5 = 3 -> offset 6.
    move.w  #0x1234, (6,%a2)
    moveq   #3, %d5
    move.w  (0,%a2,%d5.w*2), %d5
    cmp.l   #0x00001234, %d5
    bne     _fail7

    | ── Part 7: scale x4 (long) ── D5 = 3 -> offset 12.
    move.l  #0x5A5A5A5A, (12,%a2)
    moveq   #3, %d5
    move.l  (0,%a2,%d5.w*4), %d5
    cmp.l   #0x5A5A5A5A, %d5
    bne     _fail8

    | ── Part 8: scale x8 (long) ── D5 = 2 -> offset 16.
    move.l  #0xA5A5A5A5, (16,%a2)
    moveq   #2, %d5
    move.l  (0,%a2,%d5.w*8), %d5
    cmp.l   #0xA5A5A5A5, %d5
    bne     _fail9

    | ── Part 9: .L index with scale x4 ──
    | A2 = SCRATCH+0x600; D6 = 5 -> offset 20.
    lea     SCRATCH+0x600, %a2
    move.l  #0x0BADF00D, (20,%a2)
    moveq   #5, %d6
    move.l  (0,%a2,%d6.l*4), %d6
    cmp.l   #0x0BADF00D, %d6
    bne     _fail10

    | ── Part 10: An used as BOTH index and destination (MOVEA) ──
    | A3 = 8; A2 = SCRATCH+0x700; long[0x708] = 0x00123456.
    lea     SCRATCH+0x700, %a2
    move.l  #0x00123456, (8,%a2)
    movea.l #8, %a3
    movea.l (0,%a2,%a3.l), %a3
    move.l  %a3, %d0
    cmp.l   #0x00123456, %d0
    bne     _fail11

    | ── Part 11: An index aliasing the BASE register ──
    | A2 = SCRATCH+0x800 used as base AND as .L index -> EA = 2*A2.
    | Not a same-dest case, but the same read-old-value discipline; the
    | destination is D0, so this fences the base/index fanout.
    | 2*(SCRATCH+0x800) = 0x00201000.  Poison that with a known long.
    move.l  #0x00201000, %a0
    move.l  #0x7EE7BEEF, (%a0)
    lea     SCRATCH+0x800, %a2
    move.l  (0,%a2,%a2.l), %d0
    cmp.l   #0x7EE7BEEF, %d0
    bne     _fail12

    | ── Part 12: ALU op with index == destination (ADD.L <ea>,Dn) ──
    | D7 = 4; A2 = SCRATCH+0x900; long[0x904] = 0x00000010.
    | Musashi: source read from EA built with the OLD D7, then D7 += src.
    lea     SCRATCH+0x900, %a2
    move.l  #0x00000010, (4,%a2)
    moveq   #4, %d7
    add.l   (0,%a2,%d7.l), %d7
    cmp.l   #0x00000014, %d7
    bne     _fail13

    | ── Part 13: memory destination whose index is the source Dn ──
    | AND.B D0,(0,A2,D0.w) — index and source data reg alias; dest is
    | memory.  D0 = 4, A2 = SCRATCH+0xA00, byte[0xA04] = 0x0F.
    | Result byte = 0x0F & 0x04 = 0x04.
    lea     SCRATCH+0xA00, %a2
    move.b  #0x0F, (4,%a2)
    moveq   #4, %d0
    and.b   %d0, (0,%a2,%d0.w)
    move.b  (4,%a2), %d0
    and.l   #0xFF, %d0
    cmp.l   #0x00000004, %d0
    bne     _fail14

    | ── Part 14: An is BASE *and* INDEX *and* DESTINATION ──
    | movea.l (0,A4,A4.l),A4 with A4 = SCRATCH+0x800 -> EA = 2*A4
    | = 0x00201000, which Part 11 primed with 0x7EE7BEEF.
    lea     SCRATCH+0x800, %a4
    movea.l (0,%a4,%a4.l), %a4
    move.l  %a4, %d0
    cmp.l   #0x7EE7BEEF, %d0
    bne     _fail17

    | ── Part 15: MOVEM.L with the index register IN the load list ──
    | movem.l (0,A2,D0.l*4),%d0-%d1 — D0 is the index AND is reloaded.
    | Musashi computes the EA once, before any register is written.
    | A2 = SCRATCH+0xC00, D0 = 2 -> EA = SCRATCH+0xC08.
    lea     SCRATCH+0xC00, %a2
    move.l  #0x0C0C0C0C, (8,%a2)
    move.l  #0x0D0D0D0D, (12,%a2)
    moveq   #2, %d0
    moveq   #0, %d1
    movem.l (0,%a2,%d0.l*4), %d0-%d1
    cmp.l   #0x0C0C0C0C, %d0
    bne     _fail18
    cmp.l   #0x0D0D0D0D, %d1
    bne     _fail19

    | ── Part 16: different-register regression fence ──
    | move.b (0x78,A2,D1.w),D2 must be unaffected by any fix.
    lea     SCRATCH+0xB00, %a2
    move.b  #0x6D, (0x79,%a2)
    moveq   #1, %d1
    moveq   #0, %d2
    move.b  (0x78,%a2,%d1.w), %d2
    cmp.l   #0x0000006D, %d2
    bne     _fail15
    cmp.l   #0x00000001, %d1        | index register untouched
    bne     _fail16

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail0:
    move.l  #0xDEAD0000, %d7
    bra     _fail
_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d7
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_fail9:
    move.l  #0xDEAD0009, %d7
    bra     _fail
_fail10:
    move.l  #0xDEAD000A, %d7
    bra     _fail
_fail11:
    move.l  #0xDEAD000B, %d7
    bra     _fail
_fail12:
    move.l  #0xDEAD000C, %d7
    bra     _fail
_fail13:
    move.l  #0xDEAD000D, %d7
    bra     _fail
_fail14:
    move.l  #0xDEAD000E, %d7
    bra     _fail
_fail15:
    move.l  #0xDEAD000F, %d7
    bra     _fail
_fail16:
    move.l  #0xDEAD0010, %d7
    bra     _fail
_fail17:
    move.l  #0xDEAD0011, %d7
    bra     _fail
_fail18:
    move.l  #0xDEAD0012, %d7
    bra     _fail
_fail19:
    move.l  #0xDEAD0013, %d7
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
