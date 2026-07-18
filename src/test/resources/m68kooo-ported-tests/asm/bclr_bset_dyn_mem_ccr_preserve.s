| bclr_bset_dyn_mem_ccr_preserve.s — BCLR/BSET dynamic-register memory
| form (Dn,(An)) must preserve N/V/C/X, matching the BCHG dynamic-memory
| coverage added in bchg_ccr_step.s / bchg_mem_ccr_preserve.s.
|
| Motivation: bchg_ccr_step.s and bchg_mem_ccr_preserve.s originally had
| a test-authoring bug — `moveq #0,%d0` (bit-index setup) was placed
| AFTER the CCR init, and MOVEQ unconditionally clears V/C, silently
| invalidating those tests (confirmed against Musashi: the RTL's BCHG
| dynamic-memory-form CCR handling was already correct).  BCLR and BSET
| share the exact same decode crack skeleton for the dynamic-source
| memory-dst form (Stage D-4 in decode_uop_assemble.v: phase 0 "Dm AND 7
| -> TMP2", LOAD byte, ALU op with flags_wr=Z-only, STORE byte) but had
| no directed test isolating N/V/C preservation for that specific crack
| shape.  This test closes that coverage gap with the correct
| instruction ordering (bit-index register set up BEFORE the CCR init).
|
| Expected values verified against Musashi golden
| (tb/models/musashi_run):
|   BCLR Dn,(An): old bit=1 -> new bit=0, Z=NOT(1)=0 -> CCR=N|V=0x0a
|   BSET Dn,(An): old bit=0 -> new bit=1, Z=NOT(0)=1 -> CCR=N|V|Z=0x0e
|
| PASS: 0xC0FFEE00 at 0xFFFF0000.
| FAIL sentinels:
|   0xDEAD0B01  BCLR Dn,(An) wrong CCR (expect NV, 0x0a)
|   0xDEAD0B02  BCLR Dn,(An) wrong byte result (expect 0x00)
|   0xDEAD0B03  BSET Dn,(An) wrong CCR (expect NVZ, 0x0e)
|   0xDEAD0B04  BSET Dn,(An) wrong byte result (expect 0x01)

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | === BCLR Dn,(An): bit0 was 1, clears to 0, Z must be 0 ===
    move.b  #0x01, 0x00020001
    moveq   #0, %d0                       | bit index=0; set BEFORE ccr init
    lea     0x00020001, %a0               |   (MOVEQ clears V/C)
    move.w  #0x000a, %ccr                 | CCR=NV
    bclr    %d0, (%a0)
    move.w  %sr, %d3
    and.l   #0x1f, %d3
    cmp.l   #0x0a, %d3                    | expect NV, Z=0
    beq     _bclr_byte_ok
    move.l  #0xDEAD0B01, %d7
    bra     _fail
_bclr_byte_ok:
    move.b  0x00020001, %d5
    and.l   #0xff, %d5
    cmp.l   #0x00, %d5
    beq     _bset_step
    move.l  #0xDEAD0B02, %d7
    bra     _fail

_bset_step:
    | === BSET Dn,(An): bit0 was 0, sets to 1, Z must be 1 ===
    move.b  #0x00, 0x00020002
    moveq   #0, %d1                       | bit index=0; set BEFORE ccr init
    lea     0x00020002, %a0
    move.w  #0x000a, %ccr                 | CCR=NV
    bset    %d1, (%a0)
    move.w  %sr, %d4
    and.l   #0x1f, %d4
    cmp.l   #0x0e, %d4                    | expect NVZ
    beq     _bset_byte_ok
    move.l  #0xDEAD0B03, %d7
    bra     _fail
_bset_byte_ok:
    move.b  0x00020002, %d6
    and.l   #0xff, %d6
    cmp.l   #0x01, %d6
    beq     _pass
    move.l  #0xDEAD0B04, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
