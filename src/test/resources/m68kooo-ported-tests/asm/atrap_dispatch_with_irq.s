| atrap_dispatch_with_irq.s — Bug B reproducer with IRQ injection.
|
| Same dispatcher pattern as atrap_full_dispatch_replay.s, but with a
| level-1 IRQ injected via the testbench's +ipl=cycle:level harness
| during the critical exit window:
|
|   ...
|   _path_a_traps:
|     push D1, push A1
|     MOVE.W D2, D1
|     MOVE.L A2, 20(SP)        ; ★ overlay
|     ANDI.W #0x100, D2
|     BNE _dispatcher_exit
|     MOVE.B D1, D2
|     push A0
|     JSR ([0x400 + D2.W*4])   ; calls handler
|     MOVEA.L (SP)+, A0
|     MOVEA.L (SP)+, A1
|     MOVE.L (SP)+, D1
|     MOVE.L (SP)+, D2
|     MOVEA.L (SP)+, A2
|     TST.W D0
|     ADDQ.W #4, A7            ; skip SR slot
|     RTS                       ; ← IRQ injected near here would break it
|
| Per Marco's docs/bug_b_atrap_divergence.md, suspect mechanism #1 is
| "IRQ delivery during handler / dispatcher exit".  Boot non-determinism
| (same N gives wildly different states across cold boots) is consistent
| with a VIA1 IRQ arriving at a sensitive cycle inside the dispatcher.
|
| Test config: matched .args sidecar `+ipl=N:1` injects level-1 IRQ at
| cycle N.  We sweep multiple injection cycles to land in different
| windows of the dispatcher.  The handler at vec 25 (autovector 1) is a
| minimum-viable RTE-er.
|
| If at any cycle the IRQ corrupts the dispatch path, the test will
| either:
|   - fall through to _fail_dest_* (handler ran but BlockMove copy didn't)
|   - fall through to a wrong PC and never reach PASS (timeout)
|   - fire vec 11 (F-line on garbage code at random PC)
|
| Without IRQ injection (sidecar omitted) this is by-construction safe
| since the dispatcher is single-threaded and self-consistent.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | --- VBR setup ---
    lea     0x00020000, %a7              | SSP base
    move.l  #0x00010000, %d0
    movec   %d0, %vbr                    | VBR = 0x00010000
    move.l  #_dispatcher, 0x00010028     | vec 10 (A-line) → dispatcher
    move.l  #_irq_handler, 0x00010064    | vec 25 (autovector 1) → IRQ handler

    | --- Drop SR.IPL to 0 so injected IRQ at level=1 fires.
    | Boot SR is 0x2700 (S=1, IPL=7, all masked).
    move.w  #0x2000, %sr                 | S=1, IPL=0

    | --- A-trap dispatch table at 0x0400 ---
    move.l  #_handler_blockmove, 0x0420  | mem.L[0x400 + 8*4]

    | --- Source / dest buffers ---
    lea     0x00030000, %a0
    move.l  #0xAABB0000, (%a0)
    move.l  #0xAABB0001, 4(%a0)
    move.l  #0xAABB0002, 8(%a0)
    move.l  #0xAABB0003, 12(%a0)
    move.l  #0xAABB0004, 16(%a0)
    move.l  #0xAABB0005, 20(%a0)
    move.l  #0xAABB0006, 24(%a0)
    move.l  #0xAABB0007, 28(%a0)
    move.l  #0xAABB0008, 32(%a0)
    move.l  #0xAABB0009, 36(%a0)
    move.l  #0xAABB000A, 40(%a0)
    move.l  #0xAABB000B, 44(%a0)
    move.l  #0xAABB000C, 48(%a0)
    move.l  #0xAABB000D, 52(%a0)
    move.l  #0xAABB000E, 56(%a0)
    move.l  #0xAABB000F, 60(%a0)

    moveq   #8, %d7                      | iteration counter (more iters → more
                                          | windows where IRQ could land)
_iter_loop:
    | BlockMove args
    move.l  #0x40, %d0                   | count
    lea     0x00030000, %a0              | source
    lea     0x00040000, %a1              | dest

    | Caller prologue: push D0/A0/A1
    movem.l %d0/%a0-%a1, -(%a7)

    | Trigger A-line — dispatcher entry
    .word   0xA02E

_after_aline:
    | Restore caller MOVEM
    movem.l (%a7)+, %d0/%a0-%a1

    | Verify dest matches source
    lea     0x00040000, %a3
    cmp.l   #0xAABB0000, (%a3)
    bne     _fail_dest_0
    cmp.l   #0xAABB0007, 28(%a3)
    bne     _fail_dest_7
    cmp.l   #0xAABB000F, 60(%a3)
    bne     _fail_dest_F

    | Clear dest for next iteration
    move.l  #0, (%a3)
    move.l  #0, 28(%a3)
    move.l  #0, 60(%a3)

    subq.l  #1, %d7
    bgt     _iter_loop

    | All passed
    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

| =========================================================================
| Dispatcher — exact replica of ROM 0x408099B0..0x40809A18.
| =========================================================================
_dispatcher:
    move.l  %a2, -(%a7)
    move.l  %d2, -(%a7)
    movea.l 10(%a7), %a2
    move.w  (%a2)+, %d2
    cmpi.w  #0xA800, %d2
    bcs     _path_a_traps
    bra     _dispatcher_exit

_path_a_traps:
    move.l  %d1, -(%a7)
    move.l  %a1, -(%a7)
    move.w  %d2, %d1
    move.l  %a2, 20(%a7)                 | ★ overlay advanced PC
    andi.w  #0x0100, %d2
    bne     _dispatcher_exit
    move.b  %d1, %d2
    move.l  %a0, -(%a7)
    moveq   #8, %d2                       | A-trap index
    .word   0x4EB0, 0x25A1, 0x0400        | JSR ([0x400 + D2.W*4])

    | Dispatcher exit — the critical RTS-trick window where IRQ
    | injection might cause the bug to surface.
    movea.l (%a7)+, %a0
    movea.l (%a7)+, %a1
    move.l  (%a7)+, %d1
    move.l  (%a7)+, %d2
    movea.l (%a7)+, %a2
    tst.w   %d0
    addq.w  #4, %a7
    rts                                   | ★ should pop the overlay

_dispatcher_exit:
    rte

| =========================================================================
| BlockMove handler — replicates ROM inner loop.
| =========================================================================
_handler_blockmove:
    move.l  %d2, -(%a7)
    moveq   #32, %d2
    move.l  %d0, %d3
_blockmove_loop:
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    sub.l   %d2, %d3
    bge     _blockmove_loop
    move.l  (%a7)+, %d2
    rts

| =========================================================================
| IRQ handler at vec 25 (autovector 1).  Just RTEs.
| =========================================================================
_irq_handler:
    rte

_fail_dest_0:
    move.l  #0xDEADB001, %d3
    bra     _write_fail
_fail_dest_7:
    move.l  #0xDEADB007, %d3
    bra     _write_fail
_fail_dest_F:
    move.l  #0xDEADB00F, %d3
_write_fail:
    lea     PASS_SENT, %a4
    move.l  %d3, (%a4)
_halt_fail:
    bra     _halt_fail
