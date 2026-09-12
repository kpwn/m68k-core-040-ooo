| memind_unrouted_families_trap.s — a full-format MEMORY-INDIRECT EA on a
| family the microcode engine has no entry for must take a CLEAN vector-4
| ILLEGAL, never execute with the wrong destination.
|
| Two such families exist today, and both need machinery MI_RMW_ENTRY does not
| have (a branch-EU condition evaluation; a tt/dr carried through ctx):
|   Scc <ea>                 — `scs ([bd,An],od)`
|   line-E MEMORY shift      — `rol.w ([bd,An],od)`
|
| Before routing was keyed on the EA (OpSpec.eaSrcValid + the MI_UNSUPPORTED
| fail-safe), neither was in the hand-written family list, and `bad`'s leading
| !isSccOp guard forced the assembler's own illegal-gate false for Scc — so
| `scs ([bd,An],od)` wrote the EA field's REGISTER instead of memory. A silent
| wrong answer, found by fuzz seed 80 and by NOTHING in this corpus: no test
| here exercised a memory-indirect Scc at all. That is what this test closes.
|
| D0 is poisoned and checked: it is the register the old wrong-register write
| landed in (the EA field is mode 110 / reg 000), so an unchanged D0 is a real
| negative control, not just "the trap counter says 2".
| The pointer slot and the target word are also checked untouched.
|
| PASS: 0xC0FFEE00.  FAIL: 0xDEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_h4, 0x00000010          | vec 4 handler
    moveq   #0, %d5                   | trap counter

    | ([0x10,%a0],0) -> pointer at %a0+0x10, target at *(ptr)
    move.l  #0x00020000, %a0
    move.l  #0x00021000, 0x00020010   | the pointer
    move.l  #0x5A5A5A5A, 0x00021000   | the target word (must stay untouched)
    move.l  #0x11223344, %d0          | poison: the OLD wrong-register write's landing site

    lea     _c2, %a5
    scs     ([0x10,%a0],0)            | Scc <memory-indirect>  -> must trap
    bra     _fail
_c2:
    lea     _c3, %a5
    rol.w   ([0x10,%a0],0)            | line-E memory shift <memory-indirect> -> must trap
    bra     _fail
_c3:
    cmp.l   #2, %d5                   | both must have trapped
    bne     _fail
    cmp.l   #0x11223344, %d0          | no wrong-register write
    bne     _fail
    cmp.l   #0x5A5A5A5A, 0x00021000   | the target is untouched
    bne     _fail
    cmp.l   #0x00021000, 0x00020010   | the pointer is untouched
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_h4:
    addq.l  #1, %d5
    move.l  %a5, (2,%a7)              | resume at the armed continuation
    rte
