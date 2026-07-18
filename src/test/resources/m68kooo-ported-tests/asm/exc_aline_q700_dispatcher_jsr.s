| exc_aline_q700_dispatcher_jsr.s — Q700 ROM A-trap dispatcher with JSR
|
| Mirrors the FULL Q700 ROM A-trap dispatcher path including the JSR
| full-format memory-indirect call to a Toolbox handler.  This covers
| the chain that the live Q700 ROM exercises:
|   A-line opcode → vec-10 frame push → A2 = saved PC → movew (a2)+, d2
|   → push more scratch → move.l a2, sp@(20) (overwrite frame.PC_lo+fmt
|   with A2) → JSR @(0x400, D2.W*4)@(0) → toolbox handler → RTS back to
|   dispatcher → pop scratch → addq #4,sp; rts → return to overwritten
|   slot = saved_PC + 2 = _post_aline.
|
| Mirrors Q700 dispatcher at 0x408099B0..0x40809A18 + the JSR at
| 0x40809A04 + the toolbox handler return path.
|
| PASS: handler runs, dispatcher RTS-via-frame-overwrite returns
|       correctly to _post_aline where D0 = 0xC0FFEE00 PASS sentinel
|       gets written.

    .text
    .org 0

_start:
    | Vector 10 → _aline_handler.
    move.l  #_aline_handler, 0x00000028

    | Toolbox dispatch table at 0x400.  D2 = 0x47 → entry at 0x400 +
    | 0x47*4 = 0x51C.  Store toolbox_handler addr there.
    move.l  #_toolbox_handler, 0x0000051C

    lea     0x00010000, %a7
    move.w  #0x2700, %sr           | sup mode, IPL=7

    move.l  #0xAAAA1234, %d0
    move.l  #0xBBBB5678, %d1
    move.l  #0xCCCC9ABC, %d2
    move.l  #0xDDDDDEF0, %d3

_pretrap:
    .short  0xA247                 | A-line trap site (Q700 0x40803E08).
_post_aline:
    cmp.l   #0xC0FFEE00, %d0
    bne     _fail

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

| ── Toolbox handler — what the JSR memind dispatched to.  Sets D0 =
| 0xC0FFEE00 PASS marker and RTSes back to dispatcher.
_toolbox_handler:
    move.l  #0xC0FFEE00, %d0
    rts                            | back to dispatcher's _after_jsr

| ── A-line handler — Q700 dispatcher prologue + sp@(20) write +
| JSR memind + epilogue.
_aline_handler:
    move.l  %a2, -(%sp)            | push a2  → sp -= 4
    move.l  %d2, -(%sp)            | push d2  → sp -= 4
    movea.l 10(%sp), %a2           | A2 = saved PC = _pretrap

    cmp.l   #_pretrap, %a2
    bne     _fail_panic

    move.w  (%a2)+, %d2            | d2 = 0xA247; A2 = _pretrap + 2
    cmp.w   #0xA247, %d2
    bne     _fail_panic

    cmpi.w  #0xA800, %d2
    bcc     _fail_panic            | should be C set (BCS would branch)

    | Push 2 more scratch regs: D1, A1 (mirrors Q700 BCS path).
    move.l  %d1, -(%sp)            | push d1
    move.l  %a1, -(%sp)            | push a1

    | Q700: move.l a2, sp@(20).  At this point sp = handler_A7 - 16.
    | sp+20 = handler_A7 + 4 = frame.PC_lo offset.  Write A2 there.
    move.l  %a2, 20(%sp)

    | Set D2 = 0x47 (zero-extended) for the JSR.W*4 index.
    moveq   #0x47, %d2

    | Push A0 (Q700 dispatcher BCS path also pushes A0 here).
    move.l  %a0, -(%sp)

    | The exact JSR memind opcode used at Q700 0x40809A04:
    | jsr @($400, %d2:w:4)@(0) = 4eb0 25a1 0400.
    | Full-format memory-indirect with bd=word/0x400, IS=0 (use idx),
    | BS=1 (no base), pre-indexed memind, od=null.
    .word   0x4eb0, 0x25a1, 0x0400
_after_jsr:
    | After toolbox_handler RTSes, we land here.  Pop saved regs in
    | reverse order (mirrors Q700's epilogue at 0x40809A0A onwards).
    move.l  (%sp)+, %a0
    move.l  (%sp)+, %a1
    move.l  (%sp)+, %d1
    move.l  (%sp)+, %d2
    move.l  (%sp)+, %a2

    | sp now = handler_A7 (frame still at sp+0..7).
    | addq.w #4, sp advances past SR + PC_hi.
    | rts pops the overwritten frame.PC_lo+fmt slot = A2 (= _pretrap + 2)
    | = _post_aline.
    addq.w  #4, %sp
    rts                            | → _post_aline

_fail_panic:
    move.l  #0xDEAD1001, %d0
    lea     0xFFFF0000, %a0
    move.l  %d0, (%a0)
_fail_panic_halt:
    bra     _fail_panic_halt
