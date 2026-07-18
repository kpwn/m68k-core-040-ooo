| adv_alias_diff_basereg.s — Store via A0, immediate load via A1 to the same EA
|
| ASSUMPTION TESTED (iq_mem.v lines 127-140, 327-340):
|   The in-queue load-store disambiguation is REGISTER-NAME based:
|   e_pbase[j] == e_pbase[k] && e_disp[j] == e_disp[k].  A store via
|   A0 and a load via A1, BOTH resolving to the same numeric address,
|   will NOT be detected as aliasing by iq_mem.
|
|   The LSU's store commit discipline (ST_BUF until commit_store_en)
|   is supposed to preserve ordering anyway: the load cannot issue
|   before the store is architecturally committed because the LSU
|   is in ST_BUF state so iss_ready goes low.
|
|   But iss_ready is LSU-level serialisation, not per-address.  The
|   load still dispatches into iq_mem and waits for LSU idle.  If
|   there's a subtle bug where the load issues to the LSU BEFORE the
|   store retires (e.g., if ST_BUF releases iss_ready too early, or
|   if LSU idles between the store's dc_bvalid and the next
|   commit_store_en), the load gets pre-store memory.
|
| ATTACK:
|   A0 = A1 = 0x00014000.  Store 0xCAFEBABE via A0, then immediately
|   load via A1.  Expected: both models see 0xCAFEBABE.
|
|   Both values start at 0xFFFFFFFF (uninitialised memory model).
|
| DIVERGENCE MEANING: the load-after-store through different base regs
|   is reading stale memory — a write-after-read hazard of the worst kind.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    | Set A0 and A1 to the same address via two INDEPENDENT paths,
    | so they end up in DIFFERENT physical regs at rename time.
    lea     0x00014000, %a0
    lea     0x00014000, %a1

    | Store via A0 followed immediately by load via A1
    move.l  #0xCAFEBABE, %d3
    move.l  %d3, (%a0)              | STORE to 0x00014000 via A0
    move.l  (%a1), %d4              | LOAD from 0x00014000 via A1 — SAME addr, different basereg

    | D4 must read 0xCAFEBABE if the store-load ordering holds
    cmp.l   #0xCAFEBABE, %d4
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_f:
    bra     _halt_f
