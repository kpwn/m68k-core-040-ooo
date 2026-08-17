| move_ccr_dn_cdb1_arb_loss.s — MOVE.W CCR,Dn must not lose its CDB slot
|
| BUG (RED before the fix: the machine HANGS forever.  PASS after.)
|
|   `MOVE.W CCR,Dn` decodes to a single UOP_SYS / SYS_MOVE_CCR_DN uop
|   that allocates an integer physical destination but is NOT executed
|   by the ALU or the LSU.  commit.v writes Dn and wakes its consumers
|   by driving the value onto *cdb1* — the CDB the LSU also owns
|   (m68k_core_commit.vh: `cdb1_en = lsu_cdb_valid | movec_bcast_en_w`,
|   with the LSU winning the payload mux unconditionally).
|
|   commit.v arbitrated for that slot by sampling `movec_bcast_busy`
|   (= lsu_cdb_valid) in the cycle the SYS uop RETIRES — but
|   `movec_bcast_en` is a register, so the broadcast actually drives
|   cdb1 the cycle AFTER.  An LSU completion starting in that
|   intervening cycle silently stole the slot: the destination phys reg
|   was never written (it kept the stale value of its previous owner)
|   and never woke.  Every consumer of Dn then waited forever — iq_int
|   filled to 16/16 with sel_valid=0 and the machine hung with no
|   exception and no wrong answer.
|
|   First measured on fuzz seed 4571 (`make fuzz-deep
|   FUZZ_DEEP_RANDOM_DRAM=1 FUZZ_DEEP_N=6000`): SYS retire at cycle
|   14008 with bcast_busy=0, LSU cdb1 (a `-(An)` store address
|   writeback) at 14009, phys 41 never broadcast, ROB head stuck on
|   `CLR.W D2` for the remaining 186000 cycles.  The same cdb1 channel
|   carries MOVEC Rc,Rn, MOVE.W SR,Dn, MOVE USP,An and FMOVE.L
|   FPCR/FPSR,Dn, so the exposure is not CCR-specific — this test just
|   picks the cheapest unprivileged instruction on that channel.
|
| THE SHAPE THAT HITS IT
|
|   A memory-operand bitfield (multi-uop, two loads that iq_mem fences
|   to the ROB head) lets the front end run far ahead, so a YOUNGER
|   `-(An)` store is already in the LSU when the older `MOVE.W CCR,Dn`
|   finally reaches the ROB head.  Stores are exempt from that fence,
|   which is what makes the overlap reachable at all; the store's
|   address-register writeback then lands on cdb1 exactly one cycle
|   after the SYS retire.
|
|   The collision window is ONE CYCLE wide, and the offset between the
|   two events moves in coarse jumps for most perturbations (measured:
|   store alignment 2 cycles, hoisting the store base 6, an extra
|   dependent ALU op 15).  The pair of NOP counts below is the knob that
|   actually walks it one cycle at a time: NOPs before the CCR read add
|   retire slots ahead of the SYS uop, NOPs after it push the store's
|   dispatch later.  nq=2 / nr=2 is the combination measured to collide;
|   the surrounding 4x4 grid is kept so the test still brackets the
|   window after unrelated front-end timing changes.  The outer loop
|   advances both memory pools so D-cache line-fill latency and store
|   split-ness vary underneath the same instruction sequence.
|
| ARCHITECTURAL EXPECTATIONS (from the ISA, not from a DUT run) —
| identical for every instance and every iteration:
|   D1=1; `subq.l #1,%d1` -> D1=0, Z=1, N=V=C=0, X=0   => CCR = 0x04
|   D2 = 0xAAAA5555 beforehand; `MOVE.W CCR,D2` is word-sized with the
|   upper word preserved and the upper byte of the word zero
|                                                      => D2 = 0xAAAA0004
|   `CLR.W D2`                                         => D2 = 0xAAAA0000
| `bftst` only sets flags that the following `subq` overwrites, so the
| memory it reads is irrelevant to the expected values.

    .text
    .org 0

|   \nq = 2-byte NOPs between the bitfield and the CCR read
|   \nr = 2-byte NOPs between the CCR read and the store base
    .macro CCRRACE nq, nr
    move.l  #0xAAAA5555, %d2
    moveq   #27, %d3
    bftst   %a0@(345){%d3:23}      | 2 head-fenced loads: front end runs ahead
    .rept \nq
    nop
    .endr
    moveq   #1, %d1
    subq.l  #1, %d1                | CCR = 0x04 (Z set, X clear)
    move.w  %ccr, %d2              | SYS uop, dst D2, broadcast on cdb1
    .rept \nr
    nop
    .endr
    lea     %a3@(216), %a5
    move.w  %a5, %a5@-             | younger store: writeback on cdb1
    move.l  %a5, %d0               | consumer of the store writeback
    move.l  %d2, %d6               | consumer of the SYS dst   <-- HANGS HERE
    clr.w   %d2                    | merge consumer of the SYS dst
    cmp.l   #0xAAAA0004, %d6       | a lost WRITE would show up here
    bne     _fail
    cmp.l   #0xAAAA0000, %d2
    bne     _fail
    lea     %a0@(3), %a0           | walk both pools (+3 / +1 are coprime
    lea     %a3@(1), %a3           | with the 32-byte D-cache line)
    .endm

_start:
    lea     0x00080000, %a7
    lea     0x00110000, %a0        | bitfield source pool
    lea     0x00140000, %a3        | store destination pool
    move.l  #4, %a6                | outer iteration count

_loop:
    CCRRACE 0, 0
    CCRRACE 0, 1
    CCRRACE 0, 2
    CCRRACE 0, 3
    CCRRACE 1, 0
    CCRRACE 1, 1
    CCRRACE 1, 2
    CCRRACE 1, 3
    CCRRACE 2, 0
    CCRRACE 2, 1
    CCRRACE 2, 2
    CCRRACE 2, 3
    CCRRACE 3, 0
    CCRRACE 3, 1
    CCRRACE 3, 2
    CCRRACE 3, 3

    move.l  %a6, %d5
    subq.l  #1, %d5
    move.l  %d5, %a6
    tst.l   %d5
    bne     _loop

_pass:
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
