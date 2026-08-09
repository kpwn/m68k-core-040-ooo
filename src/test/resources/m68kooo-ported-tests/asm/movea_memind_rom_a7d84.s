| movea_memind_rom_a7d84.s -- the exact System 7.5.3 boot-hang frontier
|
| Hardware context (Quadra 700, System 7.5.3):
|   A driver handler at logical PC 0x000A3C7C is entered but never reaches
|   0x000A3C90.  The only thing between them is `jsr (0xA7D70).pc`, and the
|   routine at 0xA7D70 ends with:
|
|       a7d84:  movea.l ([0x0C0C],0x01B4),%a0   ; 2070 81e2 0c0c 01b4
|       a7d8c:  addq.w  #1,70(%a0)
|       a7d90:  rts
|
| 0x81E2 = full format, BS=1 (base suppressed), IS=1 (index suppressed),
| BD SIZE=%10 (word base displacement), I/IS=%010 (memory indirect, WORD
| outer displacement).  The instruction is therefore EXACTLY 8 bytes:
| opword + ext + bd + od.  A mis-sized decode would advance the PC wrong,
| walk into mid-instruction bytes, and diverge with NO exception -- which is
| what the hardware shows.
|
| This test asserts BOTH halves of that claim:
|   (a) the EA/loaded value is correct, and
|   (b) the instruction LENGTH is exactly 4 words -- via an outer
|       displacement word that is ALSO a valid `bra.s` to a fail label, so
|       an off-by-one-word decode in EITHER direction lands on the trap.
|       Stage 3 is the POSITIVE CONTROL: it proves that trap has teeth by
|       deliberately placing the same 0x6002 word after a 3-word encoding
|       and requiring the branch to actually be taken.
|
| Memory map used (harness RAM = 0x00000000..0x03FFFFFF):
|   0x00000C0C  pointer slot for the literal ROM encoding
|   0x00000D00  pointer slot for the length trap
|   0x00000D04  pointer slot for the positive control
|   0x00000B78 / 0x00000CF0 / 0x00000C0C  ROM-routine replica scaffolding

    .text
    .org 0

_start:
    | ────────────────────────────────────────────────────────────────
    | Stage 1 -- LITERAL ROM BYTES: 2070 81e2 0c0c 01b4
    |   BD = 0x0C0C (sign-extends to 0x00000C0C)
    |   P  = long at 0x00000C0C          = 0x00020000
    |   EA = P + 0x01B4                  = 0x000201B4
    |   A0 = long at EA                  = 0x0BADF00D
    | ────────────────────────────────────────────────────────────────
    lea     0x00000C0C, %a1
    move.l  #0x00020000, (%a1)
    lea     0x000201B4, %a1
    move.l  #0x0BADF00D, (%a1)
    move.l  #0x00000000, %a0
    moveq   #0, %d3

    .short  0x2070, 0x81E2, 0x0C0C, 0x01B4   | movea.l ([0x0C0C],0x01B4),%a0
    addq.l  #1, %d3                          | length witness: runs once

    cmp.l   #0x0BADF00D, %a0
    bne     _fail1
    cmp.l   #1, %d3
    bne     _fail1

    | ────────────────────────────────────────────────────────────────
    | Stage 2 -- LENGTH TRAP.  Same extension shape (0x81E2), but the
    | outer-displacement word is 0x6002, which is ALSO `bra.s +2`.
    |   BD = 0x0D00  -> P = long at 0x00000D00 = 0x00030000
    |   OD = 0x6002  = +24578
    |   EA = 0x00030000 + 0x6002 = 0x00036002
    | If the decoder sizes this at 3 words the PC lands on the OD word and
    | executes it as `bra.s`, landing on _s2_lenfail.  If it sizes it at 5
    | words the PC lands on _s2_lenfail directly.  Only a correct 4-word
    | size falls through to `bra.s _s2_ok`.
    | ────────────────────────────────────────────────────────────────
    lea     0x00000D00, %a1
    move.l  #0x00030000, (%a1)
    lea     0x00036002, %a1
    move.l  #0xFEEDFACE, (%a1)
    move.l  #0x00000000, %a1

_s2:
    .short  0x2270, 0x81E2, 0x0D00, 0x6002   | movea.l ([0x0D00],0x6002),%a1
    bra.s   _s2_ok
_s2_lenfail:
    move.l  #0xDEAD0002, %d7
    bra     _fail
_s2_ok:
    cmp.l   #0xFEEDFACE, %a1
    bne     _fail3

    | ────────────────────────────────────────────────────────────────
    | Stage 3 -- POSITIVE CONTROL for the stage-2 trap.
    | Same family, but I/IS=001 (null outer displacement) => a 3-word
    | instruction.  The identical 0x6002 word is placed right after it, so
    | it MUST be reached and MUST execute as `bra.s +2`, skipping the
    | `moveq #0x33,%d7` poison.  If the branch word were silently swallowed
    | as an extension word (i.e. if our trap could not fire), D7 would be
    | clobbered or control would land on the poison.
    |   BD = 0x0D04 -> P = long at 0x00000D04 = 0x00040000
    |   EA = P (null od) -> A2 = long at 0x00040000 = 0x5A5A1234
    | ────────────────────────────────────────────────────────────────
    lea     0x00000D04, %a1
    move.l  #0x00040000, (%a1)
    lea     0x00040000, %a1
    move.l  #0x5A5A1234, (%a1)
    move.l  #0x00000000, %a2
    move.l  #0x00000099, %d7

_s3:
    .short  0x2470, 0x81E1, 0x0D04           | movea.l ([0x0D04]),%a2  (3 words)
    .short  0x6002                           | bra.s +2  -> must EXECUTE
_s3_poison:
    moveq   #0x33, %d7                       | must be SKIPPED by the bra
_s3_ok:
    cmp.l   #0x5A5A1234, %a2
    bne     _fail4
    cmp.l   #0x00000099, %d7
    bne     _fail5                           | poison ran => trap has no teeth

    | ────────────────────────────────────────────────────────────────
    | Stage 4 -- `jsr (%a0)` register-indirect call/return, the other
    | instruction on the failing ROM path (0xA7D80).  Verify the callee
    | runs, control returns, and A7 is exactly restored across the
    | clr.l -(%sp) / jsr / addq.l #4,%sp sequence the ROM uses.
    | ────────────────────────────────────────────────────────────────
    move.l  %sp, %a5
    moveq   #0, %d4
    lea     _leaf4, %a0
    clr.l   -(%sp)
    jsr     (%a0)
    addq.l  #4, %sp
    cmp.l   #0x0000005A, %d4
    bne     _fail6
    cmp.l   %a5, %sp
    bne     _fail7

    | ────────────────────────────────────────────────────────────────
    | Stage 5 -- functional replica of the whole ROM routine at 0xA7D70,
    | called through `jsr`, asserting it RETURNS (the hardware symptom is
    | precisely that it does not).
    |   0x00000B78 = 1        (positive -> bmi.s not taken -> jsr path runs)
    |   0x00000CF0 = 0x00050000 ; struct+72 = _leaf5
    |   0x00000C0C -> 0x00020000 ; 0x000201B4 = 0x00021000 (A0 target)
    |   word at 0x00021000+70 = 0x00021046 starts at 0x1234 -> 0x1235
    | ────────────────────────────────────────────────────────────────
    lea     0x00000B78, %a1
    move.l  #0x00000001, (%a1)
    lea     0x00000CF0, %a1
    move.l  #0x00050000, (%a1)
    lea     0x00050048, %a1                  | struct + 72
    move.l  #_leaf5, (%a1)
    lea     0x000201B4, %a1
    move.l  #0x00021000, (%a1)               | A0 target pointer
    lea     0x00021046, %a1
    move.w  #0x1234, (%a1)
    moveq   #0, %d5
    move.l  %sp, %a5

    jsr     _rom_a7d70                       | must RETURN

    cmp.l   %a5, %sp
    bne     _fail8
    cmp.l   #0x000000A5, %d5
    bne     _fail9                           | inner jsr (%a0) never ran
    lea     0x00021046, %a1
    cmp.w   #0x1235, (%a1)
    bne     _fail10

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

| ── helpers ─────────────────────────────────────────────────────────
_leaf4:
    moveq   #0x5A, %d4
    rts

_leaf5:
    moveq   #0x2D, %d5
    add.l   #0x78, %d5                       | 0x2D + 0x78 = 0xA5
    rts

| Exact shape of the ROM routine at 0xA7D70.
_rom_a7d70:
    tst.l   0x00000B78
    bmi.s   _rom_skip
    clr.l   -(%sp)
    movea.l 0x00000CF0, %a0
    movea.l 72(%a0), %a0
    jsr     (%a0)
    addq.l  #4, %sp
_rom_skip:
    .short  0x2070, 0x81E2, 0x0C0C, 0x01B4   | movea.l ([0x0C0C],0x01B4),%a0
    addq.w  #1, 70(%a0)
    rts

| ── failure exits ───────────────────────────────────────────────────
_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail3:
    move.l  #0xDEAD0003, %d7
    bra     _fail
_fail4:
    move.l  #0xDEAD0004, %d7
    bra     _fail
_fail5:
    move.l  #0xDEAD0005, %d7
    bra     _fail
_fail6:
    move.l  #0xDEAD0006, %d7
    bra     _fail
_fail7:
    move.l  #0xDEAD0007, %d7
    bra     _fail
_fail8:
    move.l  #0xDEAD0008, %d7
    bra     _fail
_fail9:
    move.l  #0xDEAD0009, %d7
    bra     _fail
_fail10:
    move.l  #0xDEAD000A, %d7

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
