| adv_exc_entry_young_mem_squash.s — exception entry squashes younger memory ops
|
| The ROM frontier hit an A-line trap while younger fall-through memory
| operations were still live.  exception.v uses the shared D-cache path for
| vector reads and frame pushes, so those younger LSU/MMU operations must be
| flushed as soon as the exception becomes precise, not only after exc_done.
|
| This test puts several cache-touching loads immediately after an A-line
| opword.  They are architecturally valid only after the handler adjusts the
| stacked PC and RTEs.  During exception entry they must be squashed so the
| frame push/pop path has exclusive ownership of the LSU-side cache port.
|
| PASS: handler runs once, RTE returns, and the refetched fall-through loads
|       compute the expected sentinel.
| FAIL: handler does not run once, fall-through state leaks, or the test hangs.

    .text
    .org 0

_start:
    lea     0x00018000, %a7
    move.l  #_handler, 0x00000028   | vector 10 @ 0x28

    lea     0x00100000, %a0
    move.l  #0x11112222, (%a0)
    move.l  #0x33334444, 4(%a0)
    move.l  #0x55556666, 8(%a0)
    move.l  #0x77778888, 12(%a0)
    moveq   #0, %d7

_aline_site:
    .short  0xA06E                  | A-line trap, same hot path as ROM

    | These μops may be fetched/issued before the trap retires, but must be
    | flushed during exception entry and then re-fetched after RTE.
    move.l  (%a0), %d0
    move.l  4(%a0), %d1
    move.l  8(%a0), %d2
    move.l  12(%a0), %d3
    add.l   %d1, %d0
    add.l   %d3, %d2

    move.l  #0x44446666, %d4
    cmp.l   %d4, %d0
    bne     _fail
    move.l  #0xCCCCEEEE, %d4
    cmp.l   %d4, %d2
    bne     _fail
    cmp.l   #1, %d7
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a1)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a1)
_halt_fail:
    bra     _halt_fail

_handler:
    | Format-0 frame: SR at 0(A7), PC at 2(A7), format/vector at 6(A7).
    | A-line handlers resume after the opword by bumping stacked PC by 2.
    move.l  2(%a7), %d6
    addq.l  #2, %d6
    move.l  %d6, 2(%a7)
    addq.l  #1, %d7
    rte
