| fpu_fmovem_x_an_dynlist_fline.s — FMOVEM.X with a DYNAMIC register
|   list still traps F-line, and the static (An) form still does not.
|
| This test pins a KNOWN, DELIBERATE GAP rather than a working feature,
| so read this before "fixing" it.
|
| decode_1111.vh's fmovemx_mem_ea crack sizes its multi-uop phase chain
| from popcount(ext1[7:0]) at DECODE time.  That is only valid for a
| STATIC register list.  In the dynamic form (ext1[11]=1) the mask lives
| in a data register at run time and ext1[6:4] holds only the register
| NUMBER, so the phase count is not knowable at decode.  A decode-time
| crack fundamentally cannot express that shape.
|
| Since 2026-08-03 BOTH dynamic-list cases trap, and the trap is now
| structural (`!ext1_f3[11]` in the crack's match condition) rather than
| an accident of the old "popcount >= 1" guard:
|   * Dn = D0  (ext1[7:0] == 0x00) — the case the Q700 ROM actually
|     contains, 0x4088E768, F210 D800 = "fmovemx %a0@,%d0".  Its live
|     behaviour is unchanged: it trapped before and it traps now.
|   * Dn != D0  (ext1[7:0] == Dn<<4, non-zero) — this USED to slip past
|     the popcount guard and get mis-decoded as a static list whose
|     "mask" was the Dn index, generating 12*popcount(Dn) bytes of
|     traffic.  That was survivable only while the crack moved fake
|     data; now that FMOVEM.X moves real data it would corrupt both
|     memory and the FP register file, so it fails safe to vec 11
|     instead.  Stage 3 below pins that.
|
| This test covers the first case only, and is structured as a POSITIVE
| CONTROL followed by the negative assertion, so that it cannot pass by
| trapping for the wrong reason:
|   1. The STATIC mode-010 form must execute WITHOUT trapping (this is
|      the fix landed alongside this test).  It sets D7 = 1.
|   2. The DYNAMIC mode-010 form must then trap F-line.
|   3. The F-line handler passes only if D7 == 1, i.e. only if the trap
|      came from step 2 and not from step 1.
| If step 1 ever regresses back to trapping, this test fails (D7 == 0 in
| the handler) instead of silently passing.
|
| Encodings (verified via m68k-linux-gnu-as -m68040 / objdump):
|   F210 F080   fmovem.x %fp0,(%a0)      static list, must NOT trap
|   F210 D800   fmovem.x (%a0),%d0       dynamic list Dn=D0, must trap
|   F210 D830   fmovem.x (%a0),%d3       dynamic list Dn=D3, must trap
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD1B01 — static (An) form trapped F-line (the mode-010 gap is back)
|   0xDEAD1B02 — dynamic-list form (Dn=D0) did NOT trap
|   0xDEAD1B03 — dynamic-list form (Dn=D3, non-zero ext1[7:0]) did NOT
|                trap — the old mis-decode is back

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ FP_BUF,    0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #_fline, 0x0000002C        | vec 11 F-line
    moveq   #0, %d7                    | trap-stage witness

    | ── Positive control: the static (An) form must NOT trap. ────────
    lea     FP_BUF, %a0
    move.l  #0xDEADBEEF, 0(%a0)
    move.l  #0xDEADBEEF, 4(%a0)
    move.l  #0xDEADBEEF, 8(%a0)
    fmovem.x %fp0,(%a0)                | F210 F080
    moveq   #1, %d7                    | reached => static form is decoded

    | ── Negative assertion 1: dynamic list, Dn = D0. ─────────────────
    | The handler resumes at _stage3 (it rewrites the stacked PC), so a
    | non-trapping decode is caught by the fall-through below.
    move.l  #_stage3, %d6
    fmovem.x (%a0),%d0                 | F210 D800

    | Falling through here means no trap fired.
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1B02, %d2
    move.l  %d2, (%a1)
_h2:
    bra     _h2

_stage3:
    | ── Negative assertion 2: dynamic list, Dn = D3 (ext1[7:0] != 0). ─
    | Guard the memory the old mis-decode would have written.
    move.l  #0x5A5A5A5A, 0(%a0)
    move.l  #0x5A5A5A5A, 4(%a0)
    move.l  #0x5A5A5A5A, 8(%a0)
    moveq   #2, %d7
    move.l  #_stage4, %d6
    .short  0xF210, 0xD830             | fmovem.x (%a0),%d3

    lea     PASS_SENT, %a1
    move.l  #0xDEAD1B03, %d2
    move.l  %d2, (%a1)
_h3:
    bra     _h3

_stage4:
    | The mis-decode would have written 12*popcount(3) = 24 zero bytes.
    move.l  0(%a0), %d0
    cmp.l   #0x5A5A5A5A, %d0
    bne     _fail_dyn_wrote
    move.l  8(%a0), %d0
    cmp.l   #0x5A5A5A5A, %d0
    bne     _fail_dyn_wrote

    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_hp:
    bra     _hp

| The F-line handler resumes at the address staged in D6.  D7 witnesses
| WHICH stage trapped, so an early trap (the static form regressing) is
| reported instead of silently satisfying a later stage.
_fline:
    tst.l   %d7
    beq     _fail_early_trap
    move.l  %d6, 2(%a7)                | overwrite the stacked resume PC
    rte

_fail_dyn_wrote:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1B03, %d2
    move.l  %d2, (%a1)
_h4:
    bra     _h4

_fail_early_trap:
    lea     PASS_SENT, %a1
    move.l  #0xDEAD1B01, %d2
    move.l  %d2, (%a1)
_h1:
    bra     _h1
