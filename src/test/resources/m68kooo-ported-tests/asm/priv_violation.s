| priv_violation.s — Privilege-violation exception (vector 8).
|
| Sequence:
|   1. Boot in supervisor (SR.S=1 at reset).
|   2. Install vec-8 handler at mem[0x20] (VBR=0, vec*4 = 0x20).
|   3. Install vec-32 (TRAP #0) handler as a FAIL path.
|   4. Drop to user mode: ANDI.W #$DFFF, SR  (clears S bit).
|   5. Execute STOP — STOP is privileged, should trap to vec 8.
|   6. Handler writes C0FFEE00 and halts.
|
| PASS: vec-8 handler writes C0FFEE00.
| FAIL: STOP retires silently (privilege check not enforced) OR
|       falls through to a different path.

    .text
    .org 0

_start:
    | SSP for exception entry
    lea     0x00010000, %a7
    | Install handler for vec 8 (privilege violation) at 0x00000020
    move.l  #_priv_handler, 0x00000020
    | Install an explicit FAIL path at vec 4 (illegal) in case STOP
    | is misrouted: 4*4 = 0x10
    move.l  #_fail_handler, 0x00000010
    | Drop to user mode: clear SR.S (bit 13)
    andi.w  #0xDFFF, %sr
    | In user mode.  STOP is privileged — must trap to vec 8.
    stop    #0x2000
    | If STOP retires without faulting, control continues here.
    | Write FAIL sentinel and halt.
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

_fail_handler:
    | Wrong vector taken — flag as FAIL.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE1, %d0
    move.l  %d0, (%a0)
_halt_f2:
    bra     _halt_f2

_priv_handler:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
