| adv_dbcc_counter_wrap.s — DBcc counter transition from 0 → -1 with upper-word preserve
|
| ASSUMPTION TESTED (alu.v DBcc path, design-decision #10):
|   "DBcc decrement: writeback = {src_a[31:16], src_a[15:0]-1} on the
|   decrement path; upper word always preserved per 68k manual.  On
|   cc-true exit-path, writeback = src_a unchanged."
|
|   The 68k PRM says DBcc is:
|     if (!cc) {
|         Dn.W = Dn.W - 1;
|         if (Dn.W != -1) branch;
|     }
|
|   So the counter decrements, and branch is taken unless it hit -1.
|   Upper 16 bits of Dn MUST be preserved across the decrement.
|
| ATTACK:
|   Seed Dn = 0xABCD_0000 (upper word 0xABCD, counter=0).  Run DBRA
|   (always-false cc); expected:
|     After 1st iter: counter = -1 = 0xFFFF, upper = 0xABCD → Dn = 0xABCD_FFFF
|     Branch NOT taken (exit loop).
|
|   Verify Dn = 0xABCDFFFF and PC is past DBRA.
|
| PASS: Dn = 0xABCDFFFF after exit.
| DIVERGENCE: upper word got clobbered, or counter wrapped wrong, or
|   branch taken when it should have exited.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    move.l  #0xABCD0000, %d0         | upper=0xABCD, counter=0

_loop:
    dbra    %d0, _loop                | counter 0→-1, exit; upper must stay 0xABCD

    cmp.l   #0xABCDFFFF, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_f:
    bra     _halt_f
