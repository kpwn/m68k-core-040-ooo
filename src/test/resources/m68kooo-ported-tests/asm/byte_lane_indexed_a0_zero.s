| byte_lane_indexed_a0_zero.s — Q700 byte-lane recipe at A0=0
|
| Per codex MAME-snapshot analysis 2026-05-12, the Quadra 700 ROM
| byte-lane test runs with A0 = 0x00000000 — i.e., the test memory
| is at the very base of DRAM, which is also where the 68k reset
| vector lives.  The ROM saves (a0..a0+7) into d3/d4 before the test
| and restores at the end, so the reset vector is corrupted during
| the test but restored after.
|
| This variant matches that exact memory layout so the path under
| test exercises the SAME physical addresses as the failing HW.
| If the bug is address-dependent (e.g., low-memory aliasing with
| the boot overlay flop, or PA=0 cache aliasing), this test exposes
| it where byte_lane_indexed_dbf_loop (which uses 0x00104000) does not.
|
| WARNING: This test deliberately writes to physical addresses 0..7.
| If an exception fires during the test, the CPU will fetch a bogus
| vector and bus-error in a recoverable loop.  Don't expect graceful
| failure — investigate via waveform.

    .text
    .org 0

_start:
    | Park VBR away from 0x00000000 so test writes to (a0)=PA 0 don't
    | clobber the live exception vectors.  After VBR=0x00100000, vector
    | fetches go to 0x00100000+vec*4, not to the test's scratch area.
    move.l  #0x00100000, %d0
    movec   %d0, %vbr
    | Park a sane handler at every vector in the VBR area we just set.
    | Bus error (vec 2), addr error (vec 3), illegal (vec 4), etc all
    | redirect to _panic so a stray exception is visible as a halt.
    lea     0x00100000, %a1
    move.l  #_panic, %d0
    move.l  %d0, 0(%a1)        | reset SSP (unused, MMU-off)
    move.l  %d0, 4(%a1)        | reset PC  (unused)
    move.l  %d0, 8(%a1)        | bus error
    move.l  %d0, 12(%a1)       | addr error
    move.l  %d0, 16(%a1)       | illegal
    move.l  %d0, 20(%a1)       | zero divide
    move.l  %d0, 24(%a1)       | CHK
    move.l  %d0, 28(%a1)       | TRAPV
    move.l  %d0, 32(%a1)       | priv violation
    move.l  %d0, 36(%a1)       | trace

    | Set A0 = 0 — exactly matching Q700 ROM byte-lane test memory.
    suba.l  %a0, %a0
    moveq   #0, %d6
    move.l  #0x54696E61, %d1

    | Save scratch words (Q700 idiom).
    move.l  (%a0), %d3
    move.l  4(%a0), %d4

    move.l  %d1, (%a0)
    moveq   #3, %d2

.loop:
    move.l  #-1, 4(%a0)
    cmp.b   (0,%a0,%d2:w*1), %d1
    bne     .fail_set
    not.b   %d1
    not.b   (0,%a0,%d2:w*1)
    cmp.b   (0,%a0,%d2:w*1), %d1
    beq     .skip_fail
.fail_set:
    bset    %d2, %d6
.skip_fail:
    ror.l   #8, %d1
    dbra    %d2, .loop

    | Restore scratch.
    move.l  %d3, (%a0)
    move.l  %d4, 4(%a0)

    tst.l   %d6
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a1)
_halt:
    bra     _halt

_fail:
    move.l  %d6, %d7
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_fail:
    bra     _halt_fail

_panic:
    move.l  #0xFA000099, %d7
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a1)
_halt_panic:
    bra     _halt_panic
