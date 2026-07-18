| priv_user_andi_sr_traps.s — ANDI/ORI/EORI to SR trap in user mode.
|
| Goal: ANDI #imm,SR / ORI #imm,SR / EORI #imm,SR are all privileged
| instructions per 68040 PRM §3.1.4.  In user mode each must raise
| vec 8.  Test all three.
|
| Note: ANDI/ORI/EORI to CCR are NOT privileged (CCR is the user-mode
| half of SR).  This test specifically targets the .W (full-SR) forms.
|
| Each attempt is a 4-byte instruction (opword + immediate word).
| Handler skips PC by 4 and RTEs.
|
| PASS sentinel: 0xC0FFEE00 when counter == 3.
| FAIL sentinels:
|   0xDEAD0511 — counter != 3
|   0xDEAD0512 — wrong vector taken

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ COUNTER,   0x00000800

_start:
    lea     0x00010000, %a7
    move.l  #0, COUNTER.l
    move.l  #_priv_h, 0x00000020
    move.l  #_ill_h,  0x00000010
    move.l  #_trap_dispatch, 0x00000080

    move.l  #0x00008000, %a0
    move    %a0, %usp
    andi.w  #0xDFFF, %sr

    | Three privileged SR ops in user mode.
    andi.w  #0xFFFF, %sr                  | attempt 1: ANDI #imm,SR
    ori.w   #0x0000, %sr                  | attempt 2: ORI  #imm,SR
    eori.w  #0x0000, %sr                  | attempt 3: EORI #imm,SR

    | Verify via TRAP -> supervisor.
    trap    #0

_priv_h:
    addq.l  #1, COUNTER.l
    | ANDI/ORI/EORI to SR is 4 bytes (1 opword + 1 immediate word).
    move.l  2(%a7), %d3
    addq.l  #4, %d3
    move.l  %d3, 2(%a7)
    rte

_ill_h:
    move.l  #0xDEAD0512, 0xFFFF0000
_halt_ill:
    bra     _halt_ill

_trap_dispatch:
    move.l  COUNTER.l, %d0
    cmp.l   #3, %d0
    bne     _fail_count
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
_fail_count:
    move.l  #0xDEAD0511, 0xFFFF0000
_halt_fc:
    bra     _halt_fc
