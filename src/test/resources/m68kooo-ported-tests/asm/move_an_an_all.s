| move_an_an_all.s — Test MOVE.L (An), An for ALL An, including non-A7.
|
| Q700 ROM 0x4086abb4 is the instruction 2050 = MOVE.L (A0), A0.
| HW trace observed:
|   trace[7] pc=0x4086abb4
|   trace[8] pc=0x6db6db6d   <-- wild jump direct from MOVE.L (A0), A0 retire
|
| Where 0x6db6db6d is what was at MEM[A0_old].  The instruction was
| supposed to write that value to A0 only.  Apparently it also wrote
| to PC, causing the wild jump.
|
| Prior move_a7_a7.s test PASSED — but A7 has banked USP/SSP/ISP PRF
| slots and might not trigger the same renamer/writeback path as A0-A6.
| This test exercises ALL A0..A6 to expose the bug.
|
| For each An:
|   1. Pre-set a known target at a known RAM address.
|   2. Load An with that RAM address.
|   3. Pre-set An's RAM contents to a known sentinel.
|   4. Execute MOVE.L (An), An — should set An := sentinel.
|   5. Verify An == sentinel and PC continues sequentially.
|
| FAIL sentinels are unique per An so we can tell which one fired.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | Setup supervisor stack.
    lea     0x00020000, %a7
    move.w  #0x2700, %sr                 | S=1, IPL=7, NMI-only

    | Install illegal-instruction trap so if MOVE.L (An), An jumps to
    | a wild PC, the illegal handler catches it.
    move.l  #_fail_wild,  0x00000010     | vec 4 (illegal)

    | The "sentinel" we expect An to hold after MOVE.L (An),An.
    | Choose a value that's MISALIGNED (bit 0 = 1) so if PC gets it
    | by mistake, pc_misaligned (sim) or vec-3 address-error fires.
    | Use the same 0x6db6db6d pattern the ROM saw.
    .equ SENTINEL, 0x6db6db6d

    | ─── Test MOVE.L (A0), A0 ───
    move.l  #_a0_buf, %a0
    move.l  #SENTINEL, _a0_buf
    move.l  (%a0), %a0                   | A0 := SENTINEL
    cmp.l   #SENTINEL, %a0
    bne     _fail_a0_value
    | PC must continue here — if PC went to SENTINEL, we'd vec-4

    | ─── Test MOVE.L (A1), A1 ───
    move.l  #_a1_buf, %a1
    move.l  #SENTINEL, _a1_buf
    move.l  (%a1), %a1
    cmp.l   #SENTINEL, %a1
    bne     _fail_a1_value

    | ─── Test MOVE.L (A2), A2 ───
    move.l  #_a2_buf, %a2
    move.l  #SENTINEL, _a2_buf
    move.l  (%a2), %a2
    cmp.l   #SENTINEL, %a2
    bne     _fail_a2_value

    | ─── Test MOVE.L (A3), A3 ───
    move.l  #_a3_buf, %a3
    move.l  #SENTINEL, _a3_buf
    move.l  (%a3), %a3
    cmp.l   #SENTINEL, %a3
    bne     _fail_a3_value

    | ─── Test MOVE.L (A4), A4 ───
    move.l  #_a4_buf, %a4
    move.l  #SENTINEL, _a4_buf
    move.l  (%a4), %a4
    cmp.l   #SENTINEL, %a4
    bne     _fail_a4_value

    | ─── Test MOVE.L (A5), A5 ───
    move.l  #_a5_buf, %a5
    move.l  #SENTINEL, _a5_buf
    move.l  (%a5), %a5
    cmp.l   #SENTINEL, %a5
    bne     _fail_a5_value

    | ─── Test MOVE.L (A6), A6 ───
    move.l  #_a6_buf, %a6
    move.l  #SENTINEL, _a6_buf
    move.l  (%a6), %a6
    cmp.l   #SENTINEL, %a6
    bne     _fail_a6_value

    | All passed — emit PASS.
    move.l  #0xC0FFEE00, PASS_SENT
    bra     .

_fail_wild:
    move.l  #0xDEAD0004, PASS_SENT
    bra     .

_fail_a0_value:
    move.l  #0xDEAD00A0, PASS_SENT
    bra     .
_fail_a1_value:
    move.l  #0xDEAD00A1, PASS_SENT
    bra     .
_fail_a2_value:
    move.l  #0xDEAD00A2, PASS_SENT
    bra     .
_fail_a3_value:
    move.l  #0xDEAD00A3, PASS_SENT
    bra     .
_fail_a4_value:
    move.l  #0xDEAD00A4, PASS_SENT
    bra     .
_fail_a5_value:
    move.l  #0xDEAD00A5, PASS_SENT
    bra     .
_fail_a6_value:
    move.l  #0xDEAD00A6, PASS_SENT
    bra     .

    .align 4
_a0_buf:  .long 0
_a1_buf:  .long 0
_a2_buf:  .long 0
_a3_buf:  .long 0
_a4_buf:  .long 0
_a5_buf:  .long 0
_a6_buf:  .long 0
