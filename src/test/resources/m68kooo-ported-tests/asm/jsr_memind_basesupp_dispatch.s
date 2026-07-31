| jsr_memind_basesupp_dispatch.s — JSR ([bd,Xn*scale]) with the BASE REGISTER
| SUPPRESSED, i.e. the exact OS trap-dispatch instruction the Q700 ROM uses.
|
| WHY THIS EXISTS (task #140, 2026-07-28)
| ------------------------------------------------------------------------
| Walking the stack at a live ioResult stall recovered the call chain, and the
| ROM frame directly above the stalled poll is:
|
|     408099FA  andiw #256,%d2            ; test the ASYNC bit of the trap word
|     408099FE  bnes  0x40809A20          ; async path
|     40809A02  movel %a0,%sp@-
|     40809A04  jsr @(400,%d2:w:4)@(0)    ; <- DISPATCH through a table at $0400
|     40809A0A  moveal %sp@+,%a0          ; <- the return address seen on the stack
|
| Encoding: 4EB0 25A1 0400.  Extension 0x25A1 decodes as
|     bit15   = 0     index is a DATA register
|     b14..12 = 010   index = D2
|     bit11   = 0     index size = WORD
|     b10..9  = 10    scale = 4
|     bit8    = 1     FULL format
|     bit7    = 1     BASE REGISTER SUPPRESSED   <- the interesting part
|     bit6    = 0     index not suppressed
|     b5..4   = 10    base displacement = WORD   (0x0400 follows)
|     b2..0   = 001   memory indirect, PRE-indexed, null outer displacement
| so the effective address is  [ $0400 + D2.w*4 ]  -- one memory indirection
| through a dispatch table, with NO base register contribution.
|
| CLAUDE.md lists "JSR/JMP full-format memory-indirect" as one of only two
| legacy decode surfaces still live BY DELIBERATE DEFERRAL.  That known-weak
| instruction sits in the call chain of the stall, and the symptom fits a
| mis-computed dispatch: the JSR *returns* (execution is past it), yet the
| request's handler never ran, nothing was queued and ioResult was never
| posted -- exactly what landing on the wrong routine would produce.
|
| WHAT A FAILURE HERE WOULD MEAN: the CPU computes the dispatch EA wrongly, so
| every OS call routed through this table can silently invoke the wrong
| routine.  WHAT A PASS MEANS: only that these specific forms are right; it
| does NOT clear the whole memory-indirect surface.
|
| The encodings are hand-assembled (.short) so the bytes match the ROM exactly
| rather than whatever the assembler chooses for a mnemonic.
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD3401 — base-suppressed JSR reached the wrong routine (or not at all)
|   0xDEAD3402 — did not return to the instruction after the JSR
|   0xDEAD3403 — scale factor ignored (index not multiplied by 4)
|   0xDEAD3404 — same form via JMP landed wrong
|   0xDEAD3405 — non-suppressed base variant landed wrong
| A hang/timeout means the JSR went somewhere undefined entirely.

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

_start:
    lea     0x00010000, %a7
    moveq   #0, %d3

    | ── Build the dispatch table at $0400, exactly like the OS one ───────
    | entry index 4 -> $0400 + 4*4 = $0410
    | entry index 6 -> $0400 + 6*4 = $0418   (proves the scale is applied)
    move.l  #tgt_a, 0x410
    move.l  #tgt_b, 0x418
    | Poison the entries a MISSING scale would hit: if the index were added
    | unscaled, D2=4 would read $0404 and D2=6 would read $0406.  Point those
    | at a routine that reports the scale failure instead of hanging.
    move.l  #tgt_bad_scale, 0x404
    move.l  #tgt_bad_scale, 0x406

    | ── Case 1: the ROM's exact instruction, D2 = 4 ──────────────────────
    moveq   #4, %d2
i1:
    .short  0x4EB0, 0x25A1, 0x0400      | jsr ([$0400 + D2.w*4])
    cmp.l   #0x11111111, %d3
    bne     fail_c1

    | returning to the RIGHT place matters as much as jumping to it
    cmp.l   #0x11111111, %d3
    bne     fail_ret

    | ── Case 2: same form, D2 = 6 — proves scale*4 is honoured ───────────
    moveq   #0, %d3
    moveq   #6, %d2
i2:
    .short  0x4EB0, 0x25A1, 0x0400      | jsr ([$0400 + D2.w*4])
    cmp.l   #0x22222222, %d3
    bne     fail_c2

    | ── Case 3: the JMP sibling (4EF0), same EA ──────────────────────────
    moveq   #0, %d3
    moveq   #4, %d2
    move.l  #jmp_back, 0x410            | retarget entry 4 for the JMP case
i3:
    .short  0x4EF0, 0x25A1, 0x0400      | jmp ([$0400 + D2.w*4])
jmp_ret:
    cmp.l   #0x33333333, %d3
    bne     fail_c3

    | ── Case 4: base register NOT suppressed (bit7=0) ────────────────────
    | ext 0x25A1 & ~0x80 = 0x2521 -> base = A1 + $0000 + D2.w*4, so point A1
    | at the table.  Exercises the same crack with the base contributing.
    moveq   #0, %d3
    moveq   #4, %d2
    lea     0x00000400, %a1
    move.l  #tgt_c, 0x410
i4:
    .short  0x4EB1, 0x2521, 0x0000      | jsr ([$0000 + A1 + D2.w*4])
    cmp.l   #0x44444444, %d3
    bne     fail_c4

    move.l  #0xC0FFEE00, %d1
    move.l  %d1, PASS_SENT
    bra     .

    .align 2
tgt_a:
    move.l  #0x11111111, %d3
    rts
tgt_b:
    move.l  #0x22222222, %d3
    rts
tgt_c:
    move.l  #0x44444444, %d3
    rts
jmp_back:
    move.l  #0x33333333, %d3
    bra     jmp_ret
tgt_bad_scale:
    move.l  #0xDEAD3403, %d1
    move.l  %d1, PASS_SENT
    bra     .

fail_c1:
    move.l  #0xDEAD3401, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_ret:
    move.l  #0xDEAD3402, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c2:
    move.l  #0xDEAD3403, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c3:
    move.l  #0xDEAD3404, %d1
    move.l  %d1, PASS_SENT
    bra     .
fail_c4:
    move.l  #0xDEAD3405, %d1
    move.l  %d1, PASS_SENT
    bra     .
