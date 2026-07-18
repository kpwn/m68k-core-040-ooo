| move_mem_ccr.s — Task #47: CCR on memory-source MOVE.L
|
| Validates that `move.l <mem>,Dn` sets N/Z correctly and clears V/C
| via the LSU's own CCR CDB broadcast (no ALU_TST crack).

    .text
    .org 0

_start:
    | Install a working set of four values in RAM @ 0x00200000.
    lea     0x00200000, %a0
    move.l  #0x80000000, %d0    | negative
    move.l  %d0, (%a0)
    move.l  #0x00000000, %d0    | zero
    move.l  %d0, 4(%a0)
    move.l  #0x01234567, %d0    | positive
    move.l  %d0, 8(%a0)

    | ── Phase 1: MOVE.L (An),Dn → expect N=1, Z=0 ──
    move.l  (%a0), %d1
    bpl     _fail               | N must be set
    beq     _fail               | Z must be clear

    | ── Phase 2: MOVE.L (d16,An),Dn → expect N=0, Z=1 ──
    move.l  4(%a0), %d2
    bmi     _fail
    bne     _fail

    | ── Phase 3: MOVE.L (An),Dn (positive) ──
    lea     0x00200008, %a1
    move.l  (%a1), %d3
    bmi     _fail               | N must be clear
    beq     _fail               | Z must be clear

    | ── Phase 4: MOVE.L (xxx).L,Dn — absolute long ──
    move.l  0x00200000, %d4
    bpl     _fail
    beq     _fail

    | PASS sentinel
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt
