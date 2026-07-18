| exc_aline_q700_dispatcher.s — exact replica of Q700 ROM A-trap dispatcher
|
| The Q700 ROM Toolbox dispatcher at 0x408099B0 implements a clever RTS-
| style return-from-exception by:
|   1. Pushing scratch regs A2, D2 — read saved PC at sp@(10).
|   2. Post-incrementing A2 by 2 via `movew (a2)+, d2` (consumes opword).
|   3. Pushing more scratch regs D1, A1 — total 4 longs (16 bytes) pushed.
|   4. Writing A2 back to sp@(20), which is frame-offset-4 (= PC_lo +
|      format-word slot, overwritten with new "after the A-trap opcode" PC).
|   5. Dispatching to the right Toolbox handler (via JSR memind).
|   6. After handler RTS, popping registers in reverse and `addq.w #4, sp`
|      then RTS — this skips the SR + PC_hi (4 bytes of stale frame) and
|      pops the overwritten PC_lo+format slot as the return PC.
|
| This is a Bug B regression: the live HW shows the dispatcher RTS popping
| 0x40803E10 instead of 0x40803E0A — i.e., the saved PC pushed to the
| frame was apparently 0x40803E0E (off-by-6) instead of 0x40803E08.
|
| If our exception path saves the wrong PC, this test ends at _fail.
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.
|
| Coverage:
|   - SR=0x2700 (sup, IPL=7) at trap.
|   - VBR=0 — frame-store address starts at low RAM.
|   - Handler pushes 4 longs (16 bytes scratch) like the real Q700.
|   - Validates the dispatcher's `move.l a2, sp@(20)` overwrite + `addq.w
|     #4, sp; rts` epilogue lands on _post_aline.

    .text
    .org 0

_start:
    | Vector 10 → _aline_handler.
    move.l  #_aline_handler, 0x00000028

    | Set up stack and pre-trap state.
    lea     0x00010000, %a7
    move.w  #0x2700, %sr           | sup mode, IPL=7

    | Pre-trap registers (we'll verify these are restored after RTE-via-RTS).
    move.l  #0xAAAA1234, %d0
    move.l  #0xBBBB5678, %d1
    move.l  #0xCCCC9ABC, %d2
    move.l  #0xDDDDDEF0, %d3

_pretrap:
    | The A-line opcode 0xA247 — exactly the Q700 ROM site.
    .short  0xA247
_post_aline:
    | Verify D0 was set to 0xC0FFEE00 by the handler (our PASS marker).
    cmp.l   #0xC0FFEE00, %d0
    bne     _fail

    | PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    bra     _halt_fail

| ── A-line handler — mirrors Q700 dispatcher prologue/epilogue exactly ──
| Frame at handler entry:
|   sp+0..1 : SR
|   sp+2..5 : PC (= _pretrap)
|   sp+6..7 : format/vector word
|
| After 4 long pushes (16 bytes), the frame is at sp+16..sp+23.
| sp+16 = SR; sp+18 = PC_hi; sp+20 = PC_lo (gets overwritten); sp+22 = fmt.
| `move.l a2, sp@(20)` overwrites PC_lo+format with A2 = _pretrap+2.
| Then pop A1/D1, pop D2/A2, addq.w #4, sp, rts → PC = popped value =
| the PC_lo+format overwritten slot = A2 = _pretrap + 2 = _post_aline.

_aline_handler:
    | Q700 prologue: push A2, D2 first.
    move.l  %a2, -(%sp)           | push a2  → sp -= 4
    move.l  %d2, -(%sp)           | push d2  → sp -= 4

    | sp+10 = saved PC (= sp+8 + frame.PC_offset_2).
    movea.l 10(%sp), %a2

    | Verify A2 == _pretrap.
    cmp.l   #_pretrap, %a2
    bne     _fail_handler_wrong_pc

    | movew (a2)+, d2 — d2 = opword 0xA247, A2 = _pretrap + 2.
    move.w  (%a2)+, %d2
    cmp.w   #0xA247, %d2
    bne     _fail_handler_wrong_opword

    cmp.l   #(_pretrap + 2), %a2
    bne     _fail_handler_wrong_postinc

    cmpi.w  #0xA800, %d2
    bcc     _fail_handler_wrong_cmp_branch

    | Q700-style: push 2 more scratch regs (D1, A1) before sp@(20) write.
    move.l  %d1, -(%sp)           | push d1  → sp -= 4
    move.l  %a1, -(%sp)           | push a1  → sp -= 4

    | Now sp = handler_A7 - 16.  sp+20 = handler_A7 + 4 = frame.PC_lo
    | offset.  A2 = _pretrap + 2.  Write the post-A-line PC into the
    | frame's PC_lo slot, overwriting the dispatched-routine return.
    move.l  %a2, 20(%sp)

    | Mark D0 to PASS-sentinel value so _post_aline can verify.
    move.l  #0xC0FFEE00, %d0

    | Q700 epilogue: pop scratch regs in reverse, addq.w #4, sp, rts.
    move.l  (%sp)+, %a1           | pop a1
    move.l  (%sp)+, %d1           | pop d1
    move.l  (%sp)+, %d2           | pop d2
    move.l  (%sp)+, %a2           | pop a2

    | sp now = handler_A7 (original entry SP, frame at +0..+7).
    | addq.w #4, sp advances past SR + PC_hi (frame offset 0..3).
    | Then RTS pops 4 bytes from frame offset 4..7 = the overwritten
    | PC_lo+format word, which holds A2 = _pretrap + 2 = _post_aline.
    addq.w  #4, %sp
    rts                           | → _post_aline

| ── Handler-side failure paths.

_fail_handler_wrong_pc:
    move.l  #0xDEAD1001, %d0
    bra     _fail_handler_panic

_fail_handler_wrong_opword:
    move.l  #0xDEAD1002, %d0
    bra     _fail_handler_panic

_fail_handler_wrong_postinc:
    move.l  #0xDEAD1003, %d0
    bra     _fail_handler_panic

_fail_handler_wrong_cmp_branch:
    move.l  #0xDEAD1004, %d0
    bra     _fail_handler_panic

_fail_handler_panic:
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_fail_handler_halt:
    bra     _fail_handler_halt
