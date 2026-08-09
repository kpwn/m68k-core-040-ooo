| handle_deref_rmw_count.s -- re-deref a handle then RMW through it
|
| Reproduction shape for the System 7.5.3 "negative _SetHandleSize" hang.
|
| Measured on real HW (task: negative-sethandlesize): the OS routine at
| RAM 0x007740A8 is
|
|     7740b0  move.l  12(%fp),%d7        | delta
|     7740b4  movea.l 8(%fp),%a4         | handle
|     7740b8  movea.l (%a4),%a0          | *handle
|     7740ba  move.l  4(%a0),%d0         | byte count
|     7740be  moveq   #-4,%d1
|     7740c0  sub.l   %d1,%d0            |  + 4
|     7740c2  add.l   %d7,%d0            |  + delta
|     7740c8  jsr     SetHandleSize      | (may MOVE the block)
|     7740cc  movea.l (%a4),%a0          | RE-deref -- new location
|     7740ce  add.l   %d7,4(%a0)         | count += delta   <-- must land
|
| Measured live state at the hang (JTAG, effective halt, dcache pushed):
|   handle          0x007AF7A0   ->  block header 0x007AF808
|   block header    0x86000060   ->  tag=relocatable, corr=6, phys=96
|                                    => logical size = 96-8-6 = 82
|   4(*handle)      0x00000000   <-- the byte count
|   delta           -28          (caller does `moveq #28,%d7; neg.l %d0`)
|   => newSize = 0 + 4 - 28 = -24, rounded by ROM 0x408878EE to -16,
|      and every other negative in the register file is derived from it
|      (-112 = -16-96, -256 = 0xFFFFFF00 from `move.b` into -24,
|       0x3FFFFFE4 = -112 >>> 2).  ONE bad value, not a family.
|
| *** RETRACTED 2026-08-08 -- THE FOSSIL ARGUMENT BELOW IS WRONG. ***
| Measured on HW with a data watchpoint on the count field (0x007AF7B4):
|   * the field is written EXACTLY ONCE, by the ROM's _NewHandleClear
|     birth-clear loop at 0x4080D418 (`clr.w (%a0)+`), and NEVER again
|     right through to the ResrvMem hang.  Registers at that store:
|     D1=0x0000A322 (_NewHandleClear, sys+clear), A1=0x007AF7A0 (the
|     handle), D2=D7=0x52 (82 = the block size), A6=0x007AF370 (the zone).
|   * the block header 0x86000060 is bit-exact for an 82-byte
|     _NewHandleClear (phys=(82+8+15)&~15=96, corr=96-8-82=6), so nothing
|     overflowed into it either.
|   * the allocation is an `operator new`-style helper at RAM 0x00771EDE:
|     size in D7, `a322` at 0x00771F12; return address 0x00771F14 was on
|     the stack at the hit.
| So size 82 is the object's BIRTH size, not an accumulation of
| SetHandleSizeBy calls -- the premise that 0x007740A8 is the sole
| maintainer of both fields is false.  The count is 0 because the object
| was NEVER POPULATED, not because an update was lost or clobbered.
| No lost store, no wild write, no stale deref, no heap overflow.
| The CPU is exonerated for this datum; the open question is entirely
| ABOVE the _SetHandleSize call: why element-delete runs on an object
| whose element count is legitimately 0.
|
| Historical reasoning kept below for the record ONLY:
|
| THE FOSSIL ARGUMENT (settles lost-store vs. later-clobber):
|   The routine computes newSize from the count and THEN adds delta to
|   the count, so `size == count + 4` holds after every successful call.
|   The measured size of 82 is therefore a FOSSIL of the count arithmetic
|   at the last successful call: count_pre + 4 + delta == 82.
|     * if that call's `count += delta` store was LOST, the surviving
|       count is count_pre = 78 - delta; count == 0 requires delta == +78
|     * if the store LANDED, the count became 78 and was zeroed LATER
|   delta for this array is +/-28 (`moveq #28,%d7` in the caller at
|   0x0075C2A6), so +78 is not a plausible delta -- which favours the
|   count having been correct (78) and subsequently CLOBBERED.
|
| Second, independent argument: the caller reads the SAME count twice,
| ~15 instructions apart, with _BlockMove between them:
|     0x0075C2C0  move.l 4(%a2),%d1     | count, for the BlockMove length
|     0x0075C2C8  _BlockMove
|     0x007740BA  move.l 4(%a0),%d0     | count, for newSize
| The caller only reaches this code having located an element to delete,
| so its bookkeeping said the array was non-empty; a count of 0 at the
| second read contradicts the first.  Verified via the A5 jump table
| (a5@(770) -> jmp 0x007740A8, a5@(762) -> jmp 0x00774092), the element
| accessor is `elem = *h + 4 + offset` -- so _BlockMove's DESTINATION is
| `*h + 4 + D6`, and the count field lives at `*h + 4`.  A D6 of 0 makes
| the BlockMove destination the count field itself.
|
| (superseded) CPU store->load ordering was separately EXONERATED in sim
| (handle_relocate_rmw_same_line{,_dcache}.s pass on unmodified main), so
| the leading candidate is a WILD WRITE over the count field.
|
| This test pins the ISA-level contract for that shape:
|   * a load feeding the base register of an immediately following
|     memory-destination RMW,
|   * with the master pointer, the old block header and the old block
|     body deliberately packed into ONE 32-byte D-cache line (exactly
|     the measured HW layout: MP 0x007AF7A0 / hdr 0x007AF7A8 /
|     body 0x007AF7B0 all inside line 0x007AF7A0..BF),
|   * across a jsr/rts, and across a block relocation done with MOVE16
|     (which is what the Q700 ROM's BlockMove uses).
|
| Everything here is plain 68040 user-mode ISA -- Musashi-adjudicable.

    .text
    .org 0

    .equ    MP,       0x00104720      | master pointer   (line base)
    .equ    OLDHDR,   0x00104728      | old block header (same line)
    .equ    OLDDATA,  0x00104730      | old block body   (same line)
    .equ    NEWDATA,  0x00104790      | relocated body   (other line)
    .equ    NEWDATA2, 0x00105830      | second relocation, other set
    .equ    SCRATCH,  0x00106000      | cache-thrash region

_start:
    lea     MP, %a4

| ---------------------------------------------------------------- 1
| Baseline: deref + RMW, no relocation, all inside one cache line.
    move.l  #OLDDATA, (%a4)
    move.l  #0, OLDDATA+4
    moveq   #78, %d7
    movea.l (%a4), %a0
    .word   0xdfa8, 0x0004          | add.l %d7,4(%a0)
    move.l  OLDDATA+4, %d0
    cmp.l   #78, %d0
    bne     _fail

| ---------------------------------------------------------------- 2
| Relocate the body with MOVE16, publish the new pointer, re-deref,
| RMW.  This is the ROM BlockMove + master-pointer update shape.
    lea     OLDDATA, %a1
    lea     NEWDATA, %a2
    .word   0xf621, 0xa000          | move16 (%a1)+,(%a2)+
    move.l  #NEWDATA, (%a4)         | publish relocated pointer
    moveq   #-28, %d7
    movea.l (%a4), %a0              | re-deref  -- must see NEWDATA
    .word   0xdfa8, 0x0004          | add.l %d7,4(%a0)
    move.l  NEWDATA+4, %d0
    cmp.l   #50, %d0
    bne     _fail
    | the stale (freed) copy must NOT have been touched by the RMW
    move.l  OLDDATA+4, %d0
    cmp.l   #78, %d0
    bne     _fail

| ---------------------------------------------------------------- 3
| Same, but the relocation + publish happen inside a subroutine, so
| the re-deref load sits right after an rts (the real code shape).
    move.l  #NEWDATA, (%a4)
    move.l  #96, NEWDATA+4
    moveq   #-16, %d7
    bsr     _relocate               | copies NEWDATA -> NEWDATA2, publishes
    movea.l (%a4), %a0
    .word   0xdfa8, 0x0004          | add.l %d7,4(%a0)
    move.l  NEWDATA2+4, %d0
    cmp.l   #80, %d0
    bne     _fail

| ---------------------------------------------------------------- 4
| Accumulate through many relocations, alternating the two bodies, so
| the dirty line holding the count is evicted and refilled repeatedly.
| Any single dropped RMW shows up as a wrong total.
    move.l  #OLDDATA, (%a4)
    move.l  #0, OLDDATA+4
    move.l  #0, NEWDATA+4
    moveq   #16, %d6                | 16 rounds
    moveq   #7, %d7                 | +7 each round
    lea     SCRATCH, %a3
_loop:
    movea.l (%a4), %a0
    .word   0xdfa8, 0x0004          | add.l %d7,4(%a0)
    | relocate to the other body with MOVE16, publish
    movea.l (%a4), %a1
    move.l  (%a4), %d0
    cmp.l   #OLDDATA, %d0
    beq     _to_new
    lea     OLDDATA, %a2
    bra     _do_move
_to_new:
    lea     NEWDATA, %a2
_do_move:
    move.l  %a2, %d1
    .word   0xf621, 0xa000          | move16 (%a1)+,(%a2)+
    move.l  %d1, (%a4)              | publish
    | thrash the D-cache: 16 lines, 512 bytes, guaranteed evictions
    moveq   #15, %d5
_thrash:
    move.l  %d5, (%a3)
    lea     32(%a3), %a3
    dbra    %d5, _thrash
    lea     SCRATCH, %a3
    dbra    %d6, _loop

    | 17 rounds of +7 = 119, on whichever body is current
    movea.l (%a4), %a0
    move.l  4(%a0), %d0
    cmp.l   #119, %d0
    bne     _fail

_pass:
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, 0xFFFF0000
_halt:
    bra     _halt

_fail:
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, 0xFFFF0000
_fail_halt:
    bra     _fail_halt

| Relocate NEWDATA -> NEWDATA2 with MOVE16 and publish the new pointer.
_relocate:
    lea     NEWDATA, %a1
    lea     NEWDATA2, %a2
    .word   0xf621, 0xa000          | move16 (%a1)+,(%a2)+
    move.l  #NEWDATA2, (%a4)
    rts
