| scc_mem_byte.s — Scc Dn-direct byte-merge sanity (D-6 V2 path).
|
| Stage D-6 (agent/decode-v2-branches): validates the V2 Scc Dn-direct
| shape.  Memory Scc forms still go through legacy (D-9 territory).
|
| Layout:
|   - Set up known CCR: clear via moveq #0 (Z=1, N/V/C=0).
|   - SEQ D1 : Z=1 → low byte = 0xFF.  Preserve upper bits of D1.
|   - SNE D2 : Z=1 → low byte = 0x00.  Preserve upper bits of D2.
|   - Expect D1[7:0] = 0xFF and D1[31:8] = 0x11223344>>8 (preserved).
|   - Expect D2[7:0] = 0x00 and D2[31:8] = 0x22334455>>8 (preserved).
|
| Any wrong byte write manifests as D7 non-zero → FAIL.

    .text
    .org 0

_start:
    moveq   #0, %d7                   | failure counter

    | Seed Dn with a known upper pattern.
    move.l  #0x11223344, %d1
    move.l  #0x22334455, %d2

    | Produce Z=1, N/V/C=0.
    moveq   #0, %d0                   | Z=1 after this

    seq     %d1                       | Z=1 → D1[7:0] = 0xFF
    sne     %d2                       | Z=1 → D2[7:0] = 0x00

    | Validate D1 — expect 0x112233FF.
    cmpi.l  #0x112233FF, %d1
    beq     _d1_ok
    addq.l  #1, %d7
_d1_ok:
    | Validate D2 — expect 0x22334400.
    cmpi.l  #0x22334400, %d2
    beq     _d2_ok
    addq.l  #1, %d7
_d2_ok:

    tst.l   %d7
    beq     _pass
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt
