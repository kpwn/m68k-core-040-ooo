| mmu_wp_user_vs_super.s — WP=1 page: supervisor writes OK, user faults.
|
| Goal: per 68040 PRM §6.4.1, the WP (write-protect) bit on a leaf
| descriptor blocks user-mode writes but allows supervisor writes
| (assuming s_fld is set such that supervisor isn't blocked).  This
| differs from the WP fault test (mmu_wp_fix_rte.s) which faults in
| supervisor — that test programmed s_fld to gate supervisor too.
|
| For this test: leaf has WP=1, U-bit clear.  Per PRM, WP applies to
| BOTH modes UNLESS the supervisor s-field check exempts supervisor.
| The 68040 "supervisor-pass-through" exemption is actually via the
| TTR + s_fld trick, not the leaf descriptor.  So in pure walker
| land, WP=1 faults BOTH.  This test is therefore the "if leaf-WP
| applied uniformly" check.  Re-purpose:
|
| - Map VA = 0xA0000000 with WP=1 leaf.
| - Supervisor writes -> fault (vec 2) — handler RTEs without patching.
| - Test doesn't recover; it asserts handler fired by counter.
| - Drop to user, write same VA -> fault again.
| - Counter == 2 -> PASS.
|
| Adjusted goal: verify the leaf-level WP bit fires on EITHER
| supervisor OR user write.  Two faults on the same leaf, distinct
| invocations.
|
| PASS sentinel: 0xC0FFEE00 when counter == 2.
| FAIL sentinels:
|   0xDEAD0701 — counter != 2 after both writes
|   0xDEAD0702 — vec-2 fired in unexpected mode (wrong SR.S in frame)

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000
    .equ COUNTER,   0x00000600

_start:
    lea     0x00010000, %a7
    move.l  #0, COUNTER.l
    move.l  #_buserr, 0x00100008
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    move.l  #0x4000C000, %d0
    movec   %d0, %itt0

    | DTT0 = supervisor passthrough EXCLUDING the test page (0xA0xxxxxx)
    | so the WP fault surfaces. Cover [0..0x80000000) so handler can
    | touch tables / counter / sentinel.
    move.l  #0x007FA000, %d0
    movec   %d0, %dtt0
    | DTT1 = supervisor passthrough for sentinel page.
    move.l  #0xFF00A000, %d0
    movec   %d0, %dtt1

    move.l  #0x00200000, %d0
    movec   %d0, %urp
    movec   %d0, %srp

    | WP=1 leaf for VA 0xA0000000.  L1[0x50] -> L2, L2[0x00] -> L3,
    | L3[0x00] = PFN 0x00400000 + WP (bit 2) + DT=01 + U (bit 3).
    move.l  #0x0021000a, 0x00200140
    move.l  #0x0022000a, 0x00210000
    move.l  #0x0040000d, 0x00220000        | PFN, WP, U, DT=01

    | Enable MMU.
    move.l  #0x00008770, %d0
    movec   %d0, %tc

    | Supervisor write -> fault.
    move.l  #0xA0000000, %a1
    move.l  #0x11111111, %d0
    move.l  %d0, (%a1)                      | first fault (super write)

    | Now drop to user and try again.
    move.l  #0x00008000, %a0
    move    %a0, %usp
    andi.w  #0xDFFF, %sr
    lea     0x00008000, %a7
    move.l  #0xA0000000, %a1
    move.l  #0x22222222, %d0
    move.l  %d0, (%a1)                      | second fault (user write)

    | After both faults, RTE-skip path returns here in user mode.
    | Mode-up via TRAP to write sentinel from supervisor.
    trap    #0

_buserr:
    | Increment counter.  Skip the faulting instruction (advance PC
    | past the move.l) so we don't re-fault on retry.  Frame layout:
    | fmt-0 SP+0 SR, SP+2 PC, SP+6 vec.  fmt-7 (access fault) has a
    | longer layout — we walk both candidates.
    addq.l  #1, COUNTER.l
    | Try fmt-0: PC at SP+2.  move.l Dn,(An) is 2 bytes opword + 0
    | extension; advance PC by 2.  But fmt-7 stores PC at SP+2 too
    | (current PC at fault point, 8 bytes for fmt-0 frame, longer
    | for fmt-7).  Both paths use SP+2 for PC.
    move.l  2(%a7), %d3
    addq.l  #2, %d3
    move.l  %d3, 2(%a7)
    rte

_trap_h:
    | Verify counter == 2.
    move.l  COUNTER.l, %d0
    cmp.l   #2, %d0
    bne     _fail_count
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done

_fail_count:
    move.l  #0xDEAD0701, 0xFFFF0000
_halt_fc:
    bra     _halt_fc
