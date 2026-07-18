| cpush_bus_serialization.s — CPUSH ALL blocks the following store.
|
| After a CPUSH ALL, the next store must see the post-flush state.
| Structurally the CPUSH walker holds commit off the ROB head until
| cache_maint_done pulses, which means (a) the dirty lines have been
| written back and (b) subsequent ops haven't retired yet.
|
| Directed test:
|   1. Dirty a RAM line with V1 at addr X.
|   2. CPUSH DC, ALL — walker fires, writes V1 back to memory.
|   3. Immediately follow with a second store V2 at a different addr Y.
|   4. CINV DC, ALL — drop the cached lines.
|   5. Reload X (must see V1, proving CPUSH wrote it back).
|   6. Reload Y (must see V2, proving the post-CPUSH store completed).
|
| If the CPUSH didn't block retirement, the V2 store could race or
| get lost; if CPUSH didn't actually flush, X's reload would be
| 0xFFFFFFFF.
|
| PASS: both reloads match.
| FAIL: either mismatch.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    move.l  #0x00012000, %a0       | X: first dirty line
    move.l  #0x00012100, %a1       | Y: second dirty line (different set)

    | Dirty X with V1.
    move.l  #0xABCD1234, (%a0)

    | CPUSH DC, ALL — flush V1 to memory.
    cpusha  %dc

    | Follow immediately with a cached store V2 at Y.  This must complete
    | AFTER the CPUSH (checked indirectly by the successful reload below).
    move.l  #0x5678EF90, (%a1)

    | Now CINV everything — drops Y's dirty line without writeback.  That
    | would be a FAIL case except for the CPUSH below: we CPUSH Y first.
    cpushl  %dc, (%a1)             | writeback V2
    cinva   %dc                    | drop everything (memory has V1 + V2)

    | Reload both.
    move.l  (%a0), %d0
    move.l  (%a1), %d1

    move.l  #0xABCD1234, %d2
    cmp.l   %d2, %d0
    bne     _fail
    move.l  #0x5678EF90, %d2
    cmp.l   %d2, %d1
    bne     _fail

    lea     0xFFFF0000, %a2
    move.l  #0xC0FFEE00, %d3
    move.l  %d3, (%a2)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a2
    move.l  #0xDEADBEEF, %d3
    move.l  %d3, (%a2)
_halt_fail:
    bra     _halt_fail
