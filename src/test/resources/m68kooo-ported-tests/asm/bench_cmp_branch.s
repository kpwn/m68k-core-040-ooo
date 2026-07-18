| bench_cmp_branch.s — CMP + Bcc back-to-back throughput
|
| IPC TARGET: ~1.0-1.2 (limited by CCR stall mechanism)
| LOOP COUNT: 30 iterations = 30 CMPs + 30 BNEs
|
| This benchmark exercises the condition code register (CCR) data path:
|   1. CMP produces N/Z/V/C flags
|   2. BNE immediately reads those flags to determine branch direction
|   3. Without CCR renaming, BNE must wait for CMP to commit (CCR stall)
|
| Tight sequence:
|   cmp.l D0, D1
|   bne _target    (must wait for Z flag from CMP)
|
| According to CLAUDE.md, the CCR stall mechanism in iq_int prevents a
| flag-reader from issuing until all in-flight flag-writers have committed.
| This creates a structural serialization.
|
| Expected behavior:
|   - First few iterations warm up the predictor
|   - CMP throughput: 1 per cycle (ALU can handle it)
|   - BNE throughput: 1 per cycle (after CCR stall is resolved)
|   - Overall: 2 ops per cycle if both can issue; 1.5-1.8 with stalling
|
| This test validates CCR hazard detection and stall mechanism efficiency.

    .text
    .org 0

_start:
    move.l  #30, %d6            | loop counter
    move.l  #0x00000001, %d0    | D0 = 1
    move.l  #0x00000002, %d1    | D1 = 2

_loop:
    | Compare D0 vs D1 (sets flags; D0 != D1, so Z=0)
    cmp.l   %d1, %d0

    | Branch if not equal (depends on Z flag from CMP)
    bne     _branch_taken

    | Should not reach here (D0 != D1, so Z=0, bne taken)
    move.l  #0xDEADBEEF, %d2
    bra     _halt_fail

_branch_taken:
    | Decrement counter and loop
    sub.l   #1, %d6
    bne     _loop

    | Prepare PASS sentinel
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)

_halt:
    stop    #0x2700
    bra     _halt

_halt_fail:
    stop    #0x2700
    bra     _halt_fail
