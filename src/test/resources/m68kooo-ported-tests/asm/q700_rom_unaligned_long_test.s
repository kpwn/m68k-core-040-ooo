| q700_rom_unaligned_long_test.s — extract of the Q700 boot ROM's unaligned
| LONG store + MOVEM readback at PC 0x40847682.
|
| Reproduces the ROM's stage-1 test (long stores at byte offsets 0..4,
| readback with movem.l, compare).  HW is dropping into the operator
| prompt because D6 stays non-zero after this routine runs — meaning at
| least one of the 5 iterations gives a wrong readback.  This sim test
| isolates the kernel.
|
| Pattern per iteration (D1 = 0..4):
|   1. Seed buffer at A0 with 0x88888888 0x88888888 (two longs)
|   2. movel %d0, %a0@(0, %d1:w)    where D0 = 0x00112233
|   3. movem.l (%a0), %d2-%d3
|   4. Compare against the byte-blended expected pair
|
| If sim PASSES (D6 == 0) the LSU is RTL-correct and the HW bug is
| timing/synth; if it FAILS, the LSU has a real unaligned-store or
| MOVEM-readback bug we can fix.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | A0 = our test buffer at 0x00100000 (RAM, 4-byte aligned)
    lea     0x00100000, %a0

    | D6 starts at 0x7FFF; bclr each successful iteration.
    move.l  #0x00007FFF, %d6

    | Stage 1: long stores at offset 0..4
    move.l  #0x00112233, %d0
    moveq   #0, %d1

_loop_stage1:
    | Reset buffer to 0x88888888 0x88888888 (matches the ROM A2 source data
    | at 0x4084760E which is 0x88888888).
    move.l  #0x88888888, (%a0)
    move.l  #0x88888888, 4(%a0)

    | Unaligned long store at A0+D1
    move.l  %d0, (0, %a0, %d1:w)

    | Read back two longs via movem.l
    movem.l (%a0), %d2-%d3

    | Compute expected pair manually based on D1:
    |   D1=0: D2=0x00112233 D3=0x88888888
    |   D1=1: D2=0x88001122 D3=0x33888888
    |   D1=2: D2=0x88880011 D3=0x22338888
    |   D1=3: D2=0x88888800 D3=0x11223388
    |   D1=4: D2=0x88888888 D3=0x00112233
    cmp.l   #0, %d1
    bne     _check_d1_1
    cmp.l   #0x00112233, %d2
    bne     _fail
    cmp.l   #0x88888888, %d3
    bne     _fail
    bra     _stage1_iter_pass

_check_d1_1:
    cmp.l   #1, %d1
    bne     _check_d1_2
    cmp.l   #0x88001122, %d2
    bne     _fail
    cmp.l   #0x33888888, %d3
    bne     _fail
    bra     _stage1_iter_pass

_check_d1_2:
    cmp.l   #2, %d1
    bne     _check_d1_3
    cmp.l   #0x88880011, %d2
    bne     _fail
    cmp.l   #0x22338888, %d3
    bne     _fail
    bra     _stage1_iter_pass

_check_d1_3:
    cmp.l   #3, %d1
    bne     _check_d1_4
    cmp.l   #0x88888800, %d2
    bne     _fail
    cmp.l   #0x11223388, %d3
    bne     _fail
    bra     _stage1_iter_pass

_check_d1_4:
    cmp.l   #0x88888888, %d2
    bne     _fail
    cmp.l   #0x00112233, %d3
    bne     _fail

_stage1_iter_pass:
    bclr    %d1, %d6
    addq.l  #1, %d1
    cmp.b   #4, %d1
    bls     _loop_stage1

    | After 5 iterations bits 0..4 of D6 should be cleared.
    | Mask off everything except bits 0..4 to keep this test focused on
    | the long-store stage.
    and.l   #0x0000001F, %d6
    tst.l   %d6
    bne     _fail

    | PASS sentinel.
    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a1)
_halt:
    bra     _halt

_fail:
    | Fail sentinel — leave D2/D3 set so a tracer can see the iteration
    | that diverged.
    lea     0xFFFF0000, %a1
    move.l  #0xBADBAD00, %d1
    move.l  %d1, (%a1)
_fhlt:
    bra     _fhlt
