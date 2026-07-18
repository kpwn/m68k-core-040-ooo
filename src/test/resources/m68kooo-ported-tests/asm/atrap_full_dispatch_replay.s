| atrap_full_dispatch_replay.s — Q700 boot Bug B reproducer
|
| Mimics the EXACT instruction sequence of the ROM A-trap dispatcher
| at 0x408099B0..0x40809A18 plus a fake handler that does the
| BlockMove-shaped bulk copy + JMP-indexed tail.  Goal: trigger the
| same divergence as ROM boot, in a setting where we can debug it.
|
| Layout:
|   - Install vec-10 (A-line) handler at relocated VBR pointing to
|     our dispatcher.
|   - Trigger A-line via .word 0xA02E (replicates BlockMove A-trap).
|   - Dispatcher saves regs, does unaligned MOVE.L A2, 20(SP) overlay
|     of advanced PC, JSR ([table+D2.W*4]) to handler.
|   - Handler does eight MOVE.L (A0)+, (A1)+ + SUB+BGE inner loop
|     (matches BlockMove handler at 0x4080CB00..0x4080CB14).
|   - Handler RTSs back to dispatcher.
|   - Dispatcher unwinds + RTS-tricks back to caller.
|
| If the resulting RTS pops the wrong value, this test fails the same
| way as ROM boot — and we can attach waveform.
|
| 4 iterations to expose any cumulative state corruption.

    .text
    .org 0

_start:
    | --- VBR setup ---
    lea     0x00020000, %a7              | SSP base
    move.l  #0x00010000, %d0
    movec   %d0, %vbr                    | VBR = 0x00010000
    move.l  #_dispatcher, 0x00010028     | vec 10 (A-line) → dispatcher

    | --- Memory setup: A-trap dispatch table at 0x0400 ---
    move.l  #_handler_blockmove, 0x0420  | mem.L[0x400 + 8*4] = handler
    | (D2 will be 0x08 after dispatcher's MOVE.W (A2)+, D2 + AND #$100,D2)
    | for an A02E A-trap, D2 picks up 0x002E from the opcode, then the
    | lookup is via that index.  Simpler: use a fixed D2.

    | --- Source / dest buffers for BlockMove-style copy ---
    | Source at 0x30000, dest at 0x40000 (well above 0x10000 VBR table).
    lea     0x00030000, %a0
    | Fill source with a recognizable pattern
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

    moveq   #4, %d7                      | iteration counter
_iter_loop:
    | --- Set up BlockMove args (matching ROM convention) ---
    | Caller of A-line wrapper sets:
    |   A0 = source ptr (we'll use a wrapper-prologue MOVEM-like push)
    |   A1 = dest ptr
    |   D0 = count
    move.l  #0x40, %d0                   | count = 64 bytes
    lea     0x00030000, %a0              | source
    lea     0x00040000, %a1              | dest

    | Pre-A-line wrapper push: MOVEM.L D0/A0-A1, -(SP) (12 bytes)
    movem.l %d0/%a0-%a1, -(%a7)

    | --- Trigger A-line (vec 10) ---
    .word   0xA02E                       | _BlockMove A-trap

_after_aline:
    | After dispatcher RTS, we land here.  Restore MOVEM and verify
    | dest got the correct copy.
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
    lea     0xFFFF0000, %a4
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a4)
_halt:
    bra     _halt

| =========================================================================
| The dispatcher — replicates ROM 0x408099B0..0x40809A18 byte-for-byte.
| =========================================================================
_dispatcher:
    move.l  %a2, -(%a7)                  | 408099B0  push A2
    move.l  %d2, -(%a7)                  | 408099B2  push D2
    movea.l 10(%a7), %a2                 | 408099B4  A2 = saved PC (= A-line PC)
    move.w  (%a2)+, %d2                  | 408099B8  D2 = A-line opcode, A2 += 2
    cmpi.w  #0xA800, %d2                 | 408099BA  cmp #$A800, D2
    bcs     _path_a_traps                | 408099BE  BCS to A-trap path

    | Not A-trap (TST/Tool path) — unused in this test
    bra     _dispatcher_exit

_path_a_traps:
    | 408099F0:
    move.l  %d1, -(%a7)                  | push D1
    move.l  %a1, -(%a7)                  | push A1
    move.w  %d2, %d1                     | D1 = D2
    move.l  %a2, 20(%a7)                 | ★ overlay advanced PC at SP+20
    andi.w  #0x0100, %d2
    bne     _dispatcher_exit             | unused branch
    move.b  %d1, %d2                     | D2 = low byte of D1 (A-trap index)
    move.l  %a0, -(%a7)                  | push A0

    | The actual A-trap table dispatch.  We hand-craft the table lookup
    | because brief-form JSR via D2 with imm 0x0420 isn't easily
    | encodable from gas; use an equivalent synthesis:
    |   D2 = A-trap idx (we set 0x2E for our test); table at 0x0400
    |   target = mem.L[0x400 + D2.W*4]
    | Force D2 = 8 for our handler index (handler at table[8])
    moveq   #8, %d2
    .word   0x4EB0, 0x25A1, 0x0400       | JSR ([0x400 + D2.W*4])

    | After handler RTS, restore registers and trick exit.
    movea.l (%a7)+, %a0                  | 40809A0A
    movea.l (%a7)+, %a1                  | 40809A0C
    move.l  (%a7)+, %d1                  | 40809A0E
    move.l  (%a7)+, %d2                  | 40809A10
    movea.l (%a7)+, %a2                  | 40809A12
    tst.w   %d0                          | 40809A14
    addq.w  #4, %a7                      | 40809A16  ★ skip SR slot
    rts                                  | 40809A18  pop overlay → return PC

_dispatcher_exit:
    | Plain RTE for paths we don't exercise
    rte

| =========================================================================
| Fake BlockMove handler — replicates ROM 0x4080CB00..0x4080CB14 inner loop.
| =========================================================================
_handler_blockmove:
    | Save D2 (loop bytes-per-iteration constant)
    move.l  %d2, -(%a7)
    moveq   #32, %d2                     | bytes per inner iter (8 longs)
    move.l  %d0, %d3                     | D3 = remaining bytes
_blockmove_loop:
    move.l  (%a0)+, (%a1)+               | 0x2320 ×8 — the inner copy
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    move.l  (%a0)+, (%a1)+
    sub.l   %d2, %d3                     | 0x4080CB10  D3 -= 32
    bge     _blockmove_loop              | 0x4080CB12  loop while >= 0

    | (Skip the JMP indexed tail for now — count is exactly 64 = 2 iters)

    | Restore D2 and return
    move.l  (%a7)+, %d2
    rts

_fail_dest_0:
    move.l  #0xDEAD0001, %d3
    bra     _write_fail
_fail_dest_7:
    move.l  #0xDEAD0007, %d3
    bra     _write_fail
_fail_dest_F:
    move.l  #0xDEAD000F, %d3
    bra     _write_fail
_write_fail:
    lea     0xFFFF0000, %a4
    move.l  %d3, (%a4)
_halt_fail:
    bra     _halt_fail
