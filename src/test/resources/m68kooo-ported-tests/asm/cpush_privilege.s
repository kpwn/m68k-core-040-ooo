| cpush_privilege.s — CPUSH in user mode traps to vector 8.
|
| CPUSH (and CINV) are supervisor-only on the 68040.  Attempting them
| in user mode raises a Privilege Violation exception (vec 8).
|
| Sequence:
|   1. Supervisor: install vec-8 handler.
|   2. Install fallback vec-4 (illegal) handler as FAIL path in case
|      CPUSH is decoded as an F-line instead.
|   3. Install fallback vec-11 (F-line) handler as FAIL path in case
|      CPUSH decodes as an F-line trap (it shouldn't with the new
|      decode case, but this guards against regression).
|   4. Drop to user: ANDI.W #0xDFFF, SR  (clear SR.S).
|   5. CPUSH DC, ALL (opword 0xF478) — privileged → should trap vec 8.
|   6. Handler writes PASS sentinel.
|
| PASS: vec-8 handler fires.
| FAIL: fallthrough (no trap), or wrong vector (vec 4 / vec 11).

    .text
    .org 0

_start:
    lea     0x00010000, %a7

    | VBR = 0 at reset.  Install vec-8 handler at 0x20 (= 8 * 4).
    move.l  #_priv_handler, 0x00000020
    | Install vec-4 (illegal) handler at 0x10 as a FAIL marker.
    move.l  #_bad_vec4_handler, 0x00000010
    | Install vec-11 (F-line) handler at 0x2C as a FAIL marker.
    move.l  #_bad_vec11_handler, 0x0000002C

    | Drop to user mode.
    andi.w  #0xDFFF, %sr

    | In user mode now.  CPUSH DC, ALL — privileged, should trap vec 8.
    cpusha  %dc

    | If CPUSH didn't trap, we land here → FAIL.
_fall_through:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_ft:
    bra     _halt_ft

_bad_vec4_handler:
    | Wrong vector (illegal instead of privilege) → FAIL.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEE4, %d0
    move.l  %d0, (%a0)
_halt_v4:
    bra     _halt_v4

_bad_vec11_handler:
    | Wrong vector (F-line instead of privilege) → FAIL.
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBE11, %d0
    move.l  %d0, (%a0)
_halt_v11:
    bra     _halt_v11

_priv_handler:
    | Correct — privilege violation caught.  Write PASS sentinel.
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt_pass:
    bra     _halt_pass
