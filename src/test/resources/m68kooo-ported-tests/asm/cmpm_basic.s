| cmpm_basic.s — CMPM.L (A0)+,(A1)+ (post-increment compare-memory)
|
| CMPM computes (A1) - (A0) per PRM: the destination operand is the
| second EA (Ax).  The 3-element chain below walks two parallel
| arrays; after the third CMPM both A0 and A1 should have advanced
| by 12 bytes.
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | Set up two 3-long arrays with matching values.
    move.l  #0x00100200, %a0
    move.l  #0x00100300, %a1
    move.l  #0x01020304, %d0
    move.l  %d0, 0(%a0)
    move.l  %d0, 0(%a1)
    move.l  #0x11223344, %d0
    move.l  %d0, 4(%a0)
    move.l  %d0, 4(%a1)
    move.l  #0xAABBCCDD, %d0
    move.l  %d0, 8(%a0)
    move.l  %d0, 8(%a1)

    | First CMPM: A0 += 4, A1 += 4, Z must be 1.
    cmpm.l  (%a0)+, (%a1)+
    bne     _fail

    | Second.
    cmpm.l  (%a0)+, (%a1)+
    bne     _fail

    | Third.
    cmpm.l  (%a0)+, (%a1)+
    bne     _fail

    | Check post-increments landed: A0 should be 0x0010020C, A1
    | should be 0x0010030C.
    cmpa.l  #0x0010020C, %a0
    bne     _fail
    cmpa.l  #0x0010030C, %a1
    bne     _fail

    | ── Mismatch case: change (A1) in the 4th slot ─────────────────────
    move.l  #0x55555555, %d0
    move.l  %d0, 0(%a0)
    move.l  #0x66666666, %d1
    move.l  %d1, 0(%a1)
    cmpm.l  (%a0)+, (%a1)+
    beq     _fail                   | values differ → Z=0 → BEQ must not fire

    | ── PASS ───────────────────────────────────────────────────────────
_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a0)
_halt:
    bra     _halt

    | ── FAIL ───────────────────────────────────────────────────────────
_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d2
    move.l  %d2, (%a0)
_halt_fail:
    bra     _halt_fail
