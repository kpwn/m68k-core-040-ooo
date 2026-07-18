| rat_wrap.s — Force RAT free-list wrap
|
| PRF = 48, arch regs reset-mapped = 17 (16 + TMP0), so ~31 phys entries
| are free.  Each unique arch-reg write allocates one phys reg; writing
| to all 16 arch regs (D0..D7 + A0..A7) multiple times forces the free
| list past its wrap point.  Committed arch state must still be correct
| at the end.
|
| We re-write each arch reg twice (16 × 2 = 32 allocations) — past the
| ~31 free entries, so recycle must have happened at least once.
|
| Note: MOVE.L An,Dn is not implemented in decode; to verify An values
| we check them indirectly by using them as memory-store bases and
| reading back via a different An-indirect load (absolute-address loads
| after a store trigger a separate hang — see abs_load_after_store.s).

    .text
    .org 0

_start:
    | First pass — 16 unique writes
    move.l  #0x00000001, %d0
    move.l  #0x00000002, %d1
    move.l  #0x00000003, %d2
    move.l  #0x00000004, %d3
    move.l  #0x00000005, %d4
    move.l  #0x00000006, %d5
    move.l  #0x00000007, %d6
    move.l  #0x00000008, %d7
    move.l  #0x00000100, %a0
    move.l  #0x00000200, %a1
    move.l  #0x00000300, %a2
    move.l  #0x00000400, %a3
    move.l  #0x00000500, %a4
    move.l  #0x00000600, %a5
    move.l  #0x00000700, %a6
    move.l  #0x00010000, %a7

    | Second pass — 16 more unique writes, forcing recycle of first pass
    | phys regs.  Final arch state must reflect THESE values.
    move.l  #0xDEAD0001, %d0
    move.l  #0xDEAD0002, %d1
    move.l  #0xDEAD0003, %d2
    move.l  #0xDEAD0004, %d3
    move.l  #0xDEAD0005, %d4
    move.l  #0xDEAD0006, %d5
    move.l  #0xDEAD0007, %d6
    move.l  #0xDEAD0008, %d7
    lea     0x00104000, %a0
    lea     0x00104100, %a1
    lea     0x00104200, %a2
    lea     0x00104300, %a3
    lea     0x00104400, %a4
    lea     0x00104500, %a5
    lea     0x00104600, %a6
    | Don't rewrite A7

    | Verify D0..D7 hold second-pass values
    cmpi.l  #0xDEAD0001, %d0
    bne     _fail
    cmpi.l  #0xDEAD0002, %d1
    bne     _fail
    cmpi.l  #0xDEAD0003, %d2
    bne     _fail
    cmpi.l  #0xDEAD0004, %d3
    bne     _fail
    cmpi.l  #0xDEAD0005, %d4
    bne     _fail
    cmpi.l  #0xDEAD0006, %d5
    bne     _fail
    cmpi.l  #0xDEAD0007, %d6
    bne     _fail
    cmpi.l  #0xDEAD0008, %d7
    bne     _fail

    | Verify each An indirectly: store a unique value via each An, then
    | read back via a DIFFERENT An that we LEA to the same address.
    | Using An-indirect loads (not absolute) to avoid the abs-load-after-
    | store pipeline hang bug.
    move.l  #0xAAAA0000, %d0
    move.l  %d0, (%a0)
    move.l  #0xAAAA0101, %d0
    move.l  %d0, (%a1)
    move.l  #0xAAAA0202, %d0
    move.l  %d0, (%a2)
    move.l  #0xAAAA0303, %d0
    move.l  %d0, (%a3)
    move.l  #0xAAAA0404, %d0
    move.l  %d0, (%a4)
    move.l  #0xAAAA0505, %d0
    move.l  %d0, (%a5)
    move.l  #0xAAAA0606, %d0
    move.l  %d0, (%a6)

    | Now rebuild An pointers via LEA and read back with (An),Dn loads.
    lea     0x00104000, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0000, %d1
    bne     _fail

    lea     0x00104100, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0101, %d1
    bne     _fail

    lea     0x00104200, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0202, %d1
    bne     _fail

    lea     0x00104300, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0303, %d1
    bne     _fail

    lea     0x00104400, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0404, %d1
    bne     _fail

    lea     0x00104500, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0505, %d1
    bne     _fail

    lea     0x00104600, %a0
    move.l  (%a0), %d1
    cmpi.l  #0xAAAA0606, %d1
    bne     _fail

    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d1
    move.l  %d1, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d1
    move.l  %d1, (%a0)
_halt_fail:
    bra     _halt_fail
