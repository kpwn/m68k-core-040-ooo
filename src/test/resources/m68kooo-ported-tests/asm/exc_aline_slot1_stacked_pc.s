| exc_aline_slot1_stacked_pc.s -- an A-line decoded in SLOT 1 must stack ITS OWN pc
|
| Live hardware shape (Q700 ROM 0x40819dbc..0x40819dc8, MacsBug reports
| "unimplemented instruction" at 0x40819dc2):
|
|     40819dbc:  6104         bsr.s  0x40819dc2      <- branches to a 2-mod-4 target
|     40819dbe:  6000 0362    bra.w  ...             (skipped)
|     40819dc2:  2f2e ffd4    move.l -44(a6),-(sp)   <- SLOT 0 of the fetch group
|     40819dc6:  a873         A-line                 <- SLOT 1 of the same group
|
| The 8-byte fetch window is 0x40819dc0..dc7 and the branch target's
| addr[2:1] = 01, so one leading word is dropped and the group decodes as
| slot0 = the 2-word MOVE.L, slot1 = the A-line.
|
| If the A-line exception stacks the GROUP's slot-0 pc instead of the faulting
| packet's own pc, the handler reads the trap word from the MOVE.L's address,
| sees 0x2f2e instead of an A-line, and reports an unimplemented instruction --
| at a pc exactly 4 bytes early, which is what the hardware shows.
|
| This test asserts only that the stacked pc IS the A-line's address.

    .text
    .org 0
    bra.w   _real_start

| ---- vector 10 (A-line) lives at 0x28; keep code out of it ----

    .org 0x40
_real_start:
    lea     0x00010000, %a7
    lea     0x00020000, %a6
    move.l  #_handler, 0x00000028
    move.l  #0x12345678, -44(%a6)

    .org 0xF0
_call_site:
    bsr.s   _target                 | target is 2-mod-4 -> A-line lands in slot 1

_after:
    move.l  #0xDEAD0004, %d7        | handler must not fall through to here
    bra     _fail

| ---- the reproducing group: slot0 = 2-word MOVE.L, slot1 = A-line ----
    .org 0x102
_target:
    move.l  -44(%a6), -(%sp)        | 2f2e ffd4  @ 0x102 (slot 0)
_aline_site:
    .short  0xa873                  | a873       @ 0x106 (slot 1)

_past_aline:
    move.l  #0xDEAD0005, %d7
    bra     _fail

| ---- A-line handler: check the stacked pc ----
    .org 0x140
_handler:
    move.l  %a2, -(%sp)
    move.l  %d2, -(%sp)
    movea.l 10(%sp), %a2            | stacked pc (format 0: sr, pc.l, fmt/vec)

    cmp.l   #_aline_site, %a2
    beq     _pc_ok

    cmp.l   #_target, %a2           | the suspected failure: slot0's pc
    beq     _fail_pc_is_slot0
    move.l  #0xDEAD0003, %d7        | some other wrong pc
    bra     _fail

_pc_ok:
    move.w  (%a2), %d2              | and the word there really is the A-line
    cmp.w   #0xa873, %d2
    bne     _fail_opword
    bra     _pass

_fail_pc_is_slot0:
    move.l  #0xDEAD0001, %d7        | stacked pc pointed at the SLOT-0 instruction
    bra     _fail

_fail_opword:
    move.l  #0xDEAD0002, %d7
    bra     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
