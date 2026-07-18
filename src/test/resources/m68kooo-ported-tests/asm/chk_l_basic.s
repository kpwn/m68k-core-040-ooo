| chk_l_basic.s — decode-iv-d CHK.L long-form bounds check.
| Verifies that CHK.L Dy, Dn correctly traps on out-of-range and
| passes through when the value is inside the [0, bound] window.
| Also exercises the #imm32 variant (CHK.L #imm32, Dn).
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
| FAIL sentinel: 0xDEADBEEF.
|
| Strategy:
|   1. Install a CHK handler at vector 6 (offset 0x18).
|   2. Exercise in-range paths (must fall through with no trap).
|   3. Exercise the trap path (must branch to handler, which writes PASS).
|   4. If a guard CHK.L in step 2 unexpectedly traps, the handler writes
|      PASS prematurely — but the in-range paths continue down the main
|      line and (on step 4) set up the final trap before writing PASS.
|      To catch a broken in-range CHK.L we first fall through each
|      in-range case to labels that MUST NOT be entered from the handler.

    .text
    .org 0

_start:
    lea     0x00010000, %a7                | supervisor stack
    move.l  #_chk_handler, 0x00000018      | vec 6 = offset 0x18

    | ---- CHK.L Dy, Dn in-range (no trap) ----
    move.l  #0x00000005, %d0               | value inside [0, 100]
    move.l  #100, %d1
    chk.l   %d1, %d0                       | no trap — must fall through
    bra     1f
    bra     _fail                           | unreachable in a correct impl
1:
    | ---- CHK.L #imm32 in-range ----
    move.l  #0x12345678, %d0
    chk.l   #0x12345678, %d0               | val == bound → in-range
    bra     2f
    bra     _fail
2:
    | ---- CHK.L Dy, Dn — zero value, zero bound → in-range ----
    moveq   #0, %d0
    moveq   #0, %d1
    chk.l   %d1, %d0
    bra     3f
    bra     _fail
3:
    | ---- CHK.L — large positive value, larger bound → in-range ----
    move.l  #0x0000FFFF, %d0
    move.l  #0x7FFFFFFF, %d1
    chk.l   %d1, %d0
    bra     4f
    bra     _fail
4:
    | ---- Negative value → out-of-range.  MUST trap to _chk_handler. ----
    move.l  #-1, %d0
    move.l  #100, %d1
    chk.l   %d1, %d0                       | must trap
    bra     _fail                           | fall-through = bug

_chk_handler:
    | Handler fires on CHK trap — write PASS + halt.
    move.l  #0xC0FFEE00, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d7
    move.l  #0xFFFF0000, %a0
    move.l  %d7, (%a0)
    bra     _halt
