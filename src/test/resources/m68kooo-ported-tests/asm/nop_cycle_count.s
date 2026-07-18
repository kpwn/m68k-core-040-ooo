| nop_cycle_count.s — 10 NOPs commit without state change (Stage D-7)
|
| Verifies V2 emits UOP_NOP for 0x4E71: no register, memory, flag, or
| supervisor side-effect.  10 NOPs back-to-back followed by a compare
| against pre-NOP fingerprint + a Bcc-on-pre-NOP-Z that requires NOPs
| to NOT disturb the Z flag.
|
| NOP test shape:
|   * Load D0/D1 with distinctive values.
|   * Set up a CCR with Z=0 via ADDI (1+1=2).
|   * Run 10 NOPs.
|   * A BEQ after the NOPs must NOT branch (Z is still 0).  If NOP
|     accidentally clears/sets CCR (e.g. V2 bug routes NOP through
|     ALU_MOV_MERGE), the Z would change and BEQ would divert.
|   * Final cmp.l of D0/D1 against their pre-NOP values catches any
|     register corruption.
|
| PASS: state preserved → C0FFEE00.
| FAIL: any change → DEADBEEF.

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | Pre-NOP fingerprint in data registers.
    move.l  #0xCAFEBABE, %d0
    move.l  #0x0BADF00D, %d1

    | Set CCR via a non-zero ADD so Z=0.
    moveq   #1, %d2
    addi.l  #1, %d2             | D2 = 2, Z=0, N=0
    | (Sanity) verify Z=0 pre-NOPs.
    beq     _fail

    | 10 NOPs.
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop

    | NOPs must not have touched Z.  If NOP accidentally cleared D2 or
    | set Z=1, this BEQ would divert to _fail.
    beq     _fail

    | Data register fingerprint must still match.
    cmp.l   #0xCAFEBABE, %d0
    bne     _fail
    cmp.l   #0x0BADF00D, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d5
    move.l  %d5, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d5
    move.l  %d5, (%a0)
_halt_fail:
    bra     _halt_fail
