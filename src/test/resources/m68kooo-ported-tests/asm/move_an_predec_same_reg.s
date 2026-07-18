| move_an_predec_same_reg.s — MOVE.L An,-(An) same-register aliasing.
|
| Bug context (HW ROM-boot crash at exc_count ~0x12ca): the Q700 ROM's
| ADB completion dispatcher (ROM 0x4080a45e) saves the stack pointer
| with the idiom
|     MOVE.L A7,-(A7)          | 0x2F0F — push saved SP
| and later restores it in the completion glue (ROM 0x408855e4):
|     MOVEA.L (A7),A7
|     RTS
| Correct 68040 semantics (Musashi golden, verified via musashi_run):
| the value STORED is the PRE-decrement register value; the register
| ends up decremented.  I.e. with A7=0x20000:
|     mem[0x1FFFC] = 0x00020000, A7 = 0x0001FFFC.
|
| The RTL decode crack for MOVE reg,-(An) is
|     phase0: TST src            (flags)
|     phase1: SUB  #delta, An    (renames An)
|     phase2: STORE src -> (An)
| When src == An, phase2's data operand renames to the post-SUB
| physical register and the DECREMENTED value is stored — producing a
| self-pointing word (mem[slot] = slot) and, in the ROM, an RTS into
| the stack.
|
| Golden values (Musashi, tb/models/musashi_run):
|   A1 = 0x00010000; MOVE.L A1,-(A1)
|     -> mem[0x0000FFFC] = 0x00010000, A1 = 0x0000FFFC
|   A7 = 0x00020000; MOVE.L A7,-(A7); MOVEA.L (A7),A7
|     -> A7 = 0x00020000 (full ROM idiom restores original SP)
| Also covers the .W variant and the postinc dual (already-correct
| ordering) as a regression fence.

    .text
    .org 0

    .equ SCRATCH, 0x00100000

_start:
    | ── Part 0: PEA (A7) — pushed value must be PRE-decrement A7 ──
    | (same hazard family: EA source register == predec'd stack base;
    |  Musashi: ea computed from A7 BEFORE the push decrement)
    move.l  %sp,%d6                 | save harness SP
    lea     SCRATCH+0x80, %sp
    pea     (%sp)
    move.l  (%sp),%d0
    cmp.l   #SCRATCH+0x80, %d0      | pushed value = ORIGINAL A7
    bne     _fail0
    | ── Part 0b: PEA d16(A7) — EA must use PRE-decrement A7 ──
    | (ROM ADB dispatcher 0x4080a4a8 does PEA 12(A7); a post-SUB base
    |  would push A7+8 instead of A7+12)
    lea     SCRATCH+0x90, %sp
    pea     0x10(%sp)
    move.l  (%sp),%d0
    cmp.l   #SCRATCH+0xA0, %d0      | pushed EA = ORIGINAL A7 + 0x10
    bne     _fail8
    move.l  %d6,%sp                 | restore harness SP

    | ── Part 1: MOVE.L A1,-(A1) — stored value must be PRE-decrement ──
    lea     SCRATCH+0x100, %a1
    move.l  %a1,-(%a1)
    move.l  %a1,%d1
    cmp.l   #SCRATCH+0xFC, %d1      | A1 must be decremented by 4
    bne     _fail1
    move.l  (%a1),%d0
    cmp.l   #SCRATCH+0x100, %d0     | stored value = ORIGINAL A1
    bne     _fail2

    | ── Part 2: full ROM idiom on A7 ──
    move.l  %sp,%d6                 | save harness SP
    lea     SCRATCH+0x200, %sp
    move.l  %sp,-(%sp)              | ROM 0x4080a45e idiom
    movea.l (%sp),%sp               | ROM 0x408855e4 idiom
    move.l  %sp,%d2
    cmp.l   #SCRATCH+0x200, %d2     | SP must be restored to original
    bne     _fail3

    | ── Part 3: MOVE.W A1,-(A1) — same aliasing, word size ──
    lea     SCRATCH+0x300, %a1
    move.w  %a1,-(%a1)
    move.l  %a1,%d3
    cmp.l   #SCRATCH+0x2FE, %d3     | A1 decremented by 2
    bne     _fail4
    move.w  (%a1),%d4
    and.l   #0xFFFF, %d4
    cmp.l   #0x0300, %d4            | low word of ORIGINAL A1 (0x100300 & 0xFFFF)
    bne     _fail5

    | ── Part 4: postinc dual MOVE.L A1,(A1)+ — stored value is original ──
    lea     SCRATCH+0x400, %a1
    move.l  %a1,(%a1)+
    move.l  %a1,%d5
    cmp.l   #SCRATCH+0x404, %d5     | A1 incremented by 4
    bne     _fail6
    move.l  -4(%a1),%d5
    cmp.l   #SCRATCH+0x400, %d5     | stored value = ORIGINAL A1
    bne     _fail7

    move.l  %d6,%sp                 | restore harness SP

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail0:
    move.l  #0xDEAD0000, %d7
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d7
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
_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
