| priv_user_movec_traps.s — every privileged MOVEC traps in user mode.
|
| Goal: in user mode, MOVEC to/from any control register raises vec 8
| (privilege violation) per 68040 PRM §3.1.4.  Test every CR-id we
| support: VBR (0x801), CACR (0x002), SFC (0x000), DFC (0x001), URP
| (0x806), and one extra (TC = 0x003) for full coverage.
|
| Strategy: each MOVEC attempt has its own vec-8 handler entry point
| that increments a counter and skips the faulting instruction.  After
| all 6 attempts, counter == 6 -> PASS.
|
| The handler "skip" trick: read the stacked PC (frame fmt-0 SP+2),
| add the instruction length (4 bytes for MOVEC), write back, RTE.
| MOVEC is 2 opwords = 4 bytes.
|
| Construction loop: the simplest is to enumerate inline.
|
| PASS sentinel: 0xC0FFEE00 when counter == 6.
| FAIL sentinels:
|   0xDEAD0501 — counter != 6 after all attempts
|   0xDEAD0502 — wrong vector taken (e.g. illegal vec 4)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ COUNTER,   0x00000700
    .equ TARGET,    0x00000704

_start:
    lea     0x00010000, %a7
    move.l  #0, COUNTER.l
    move.l  #_priv_h, 0x00000020         | vec 8 (privilege)
    move.l  #_ill_h,  0x00000010         | vec 4 (illegal — fail path)
    move.l  #_trap_dispatch, 0x00000080  | vec 32 (TRAP #0)

    | USP for user-mode A7.
    move.l  #0x00008000, %a0
    move    %a0, %usp

    | Drop to user.
    andi.w  #0xDFFF, %sr

    | Six MOVEC attempts.  Each is 4 bytes; handler skips by 4.
_user_seq:
    movec   %vbr,  %d0                    | MOVEC VBR  -> Dn  -- attempt 1
    movec   %cacr, %d0                    | MOVEC CACR -> Dn  -- attempt 2
    movec   %sfc,  %d0                    | MOVEC SFC  -> Dn  -- attempt 3
    movec   %dfc,  %d0                    | MOVEC DFC  -> Dn  -- attempt 4
    movec   %urp,  %d0                    | MOVEC URP  -> Dn  -- attempt 5
    movec   %tc,   %d0                    | MOVEC TC   -> Dn  -- attempt 6

    | After all six should have trapped.
    | Re-enter supervisor via TRAP to read counter.
    trap    #0

_priv_h:
    | Increment counter, skip the 4-byte MOVEC.
    addq.l  #1, COUNTER.l
    move.l  2(%a7), %d3
    addq.l  #4, %d3
    move.l  %d3, 2(%a7)
    rte

_ill_h:
    | Wrong vec — record fail.
    move.l  #0xDEAD0502, 0xFFFF0000
_halt_ill:
    bra     _halt_ill

| TRAP #0 handler — supervisor mode, verify counter == 6.
_trap_dispatch:
    move.l  COUNTER.l, %d0
    cmp.l   #6, %d0
    bne     _fail_count
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
_fail_count:
    move.l  #0xDEAD0501, 0xFFFF0000
_halt_fc:
    bra     _halt_fc
