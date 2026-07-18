| irq_during_postinc_loop.s — IRQ injected during a tight postinc copy loop.
|
| Minimal repro for the divergence found in atrap_dispatch_with_irq.s:
| even WITHOUT the A-trap dispatcher, an IRQ injected mid-loop causes
| the loop to never terminate.  This isolates the bug to the inner-loop
| pattern itself (8x MOVE.L (A0)+,(A1)+ + SUB+BGE), independent of the
| A-trap RTS-trick exit.
|
| With +ipl=1000:1 sidecar, this should expose whether the bug is:
|   (a) IRQ-takes-during-LSU-tight-loop corrupts D3 / A0 / A1 mapping
|   (b) IRQ-during-postinc-stream causes a store-buffer or RAT race
|   (c) Some interaction with rob_is_last_uop gating + multi-µop store crack
|
| Without the .args sidecar this passes by construction (no IRQ).

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    | Drop SR.IPL to 0 so injected IRQ fires.
    move.w  #0x2000, %sr

    | VBR setup
    lea     0x00020000, %a7
    move.l  #0x00010000, %d0
    movec   %d0, %vbr
    move.l  #_irq_handler, 0x00010064  | vec 25

    | Source/dest buffers
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

    | Run the BlockMove-style loop multiple times so IRQ injection
    | at various cycles lands inside the loop body somewhere.
    moveq   #8, %d7                | iteration counter
_iter_loop:
    moveq   #32, %d2                | bytes per inner iter
    move.l  #64, %d3                | total bytes
    lea     0x00030000, %a0         | source
    lea     0x00040000, %a1         | dest

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

    | Verify a key dest byte just to make sure copy worked
    cmp.l   #0xAABB0000, 0x00040000

    subq.l  #1, %d7
    bgt     _iter_loop

    | PASS
    lea     PASS_SENT, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

_irq_handler:
    rte
