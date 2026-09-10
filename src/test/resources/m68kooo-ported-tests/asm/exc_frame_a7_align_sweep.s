| exc_frame_a7_align_sweep.s — take a real exception at EVERY even A7 alignment
| within a 16-byte cache line, and verify the frame round-trips.
|
| Hardware capture 2026-09-10: the FIRST fault of a 7.5.3 boot collapse was a bus
| error at ROM 0x40827FE8 with A0 = 0x26FA4080 -- a plausible 0x4080xxxx pointer
| SHIFTED DOWN BY TWO BYTES. A7 was 0x007C56B2 (2-mod-4), and the long pop that
| produced A0 read from 0x007C56AE = offset 14 in a 16-byte line, i.e. spanning
| the boundary. Line-A traps were firing immediately before it.
|
| DcacheByteLane.extract is known to WRAP a line-crossing index; the RTE
| frame-word read and the FRESTORE header were split for exactly that, but the
| note on that fix still lists unguarded Size.LONG reads. This sweep drives an
| exception frame across every alignment so a straddling push or pop is exercised
| rather than assumed.
|
| For each even alignment 0..14 the test:
|   1. sets A7 = base + alignment
|   2. seeds D3 with a known value and A2 with a known pointer
|   3. executes an A-line instruction -> format-$0 frame pushed at that A7
|   4. the handler steps the stacked PC past the 2-byte opword and RTEs
|   5. verifies A7 came back exactly, and D3/A2 survived
|
| Sentinel: 0xC0FFEE00 pass; 0xDEA1nn00|align = which alignment failed and how
|   nn=01 A7 not restored, 02 D3 clobbered, 03 A2 clobbered

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ VBASE,     0x00100000
    .equ STKTOP,    0x00010800

_start:
    lea     0x00010000, %a7
    move.l  #VBASE, %d0
    movec   %d0, %vbr
    move.l  #_aline, VBASE+0x28        | vector 10 = A-line lives at 0x28, NOT 0x2C (F-line)

    moveq   #0, %d5                     | alignment index 0,2,..,14
_loop:
    | A7 = STKTOP + alignment
    move.l  #STKTOP, %d6
    add.l   %d5, %d6
    movea.l %d6, %a7
    move.l  %a7, %d4                    | remember it

    move.l  #0x33333333, %d3
    movea.l #0x22222222, %a2

    .short  0xA000                      | A-line -> frame pushed at this A7

    | A7 must be exactly back
    move.l  %a7, %d7
    cmp.l   %d4, %d7
    bne     _f1
    cmp.l   #0x33333333, %d3
    bne     _f2
    move.l  %a2, %d7
    cmp.l   #0x22222222, %d7
    bne     _f3

    addq.l  #2, %d5
    cmp.l   #16, %d5
    blt     _loop

_pass:
    lea     0x00010000, %a7
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

| A-line handler: a line-1010 exception stacks the address OF the trapping
| instruction, so step the stacked PC past the 2-byte opword before returning.
_aline:
    addq.l  #2, 2(%sp)
    rte

_f1:
    move.l  #0xDEA10100, %d7
    bra     _fail
_f2:
    move.l  #0xDEA10200, %d7
    bra     _fail
_f3:
    move.l  #0xDEA10300, %d7
_fail:
    or.l    %d5, %d7                    | which alignment
    lea     0x00010000, %a7
    lea     PASS_SENT, %a0
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
