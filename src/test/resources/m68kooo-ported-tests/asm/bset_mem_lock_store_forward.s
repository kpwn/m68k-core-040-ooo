| bset_mem_lock_store_forward.s -- the Mac OS file-system lock protocol.
|
| WHY THIS EXISTS
|
| System 7.5.3's RAM-based File Manager patch guards itself with a
| test-and-set on the low-memory byte at $0360:
|
|     9f062: movew  %sr,%sp@-
|     9f064: oriw   #0x0700,%sr      | mask to level 7
|     9f068: lea    0x360,%a1
|     9f06e: bset   #0,0x360         | Z = the OLD bit
|     9f074: beqs   got_it           | Z set => the bit WAS clear => acquired
|     9f076: movew  %sp@+,%sr        | else someone else holds it
|     9f078: moveq  #0,%d0
|     9f07a: rts
|
| The whole protocol rests on BSET-to-memory reporting the bit's value as it
| was BEFORE the write.  If Z comes out wrong -- most plausibly because the
| read half of the read-modify-write does not forward a store to the same
| byte still sitting in the store queue -- the caller concludes it failed to
| acquire a lock that its own store just took.  Nobody holds it, nobody
| releases it, and every later file-system call queues behind it forever.
|
| That is exactly the state captured on hardware on 2026-09-09: bit 0 of
| $0360 set, the file-system queue holding one request at head and tail, the
| disk driver idle with an empty queue, and the Finder spinning on ioResult.
|
| Scope: BSET/BCLR on an absolute long address, checking the Z flag against
| the PRE-write bit, with the preceding store to the same byte at varying
| distances so store-to-load forwarding in the RMW is stressed at each one.

    .text
    .org 0

    .equ LOCK, 0x00000360

_start:
    | ---- 1. bit already SET: bset must report Z=0 and leave it set -------
    move.b  #0x01, LOCK
    bset    #0, LOCK
    beq     _fail1
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail1

    | ---- 2. store CLEARS it, bset immediately after (distance 0) --------
    | The acquire case.  Z must be SET (bit was clear) and the byte must
    | come out with the bit set.
    move.b  #0x00, LOCK
    bset    #0, LOCK
    bne     _fail2
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail2

    | ---- 3. same, one unrelated instruction between store and bset ------
    move.b  #0x00, LOCK
    nop
    bset    #0, LOCK
    bne     _fail3
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail3

    | ---- 4. same, two instructions of separation ------------------------
    move.b  #0x00, LOCK
    nop
    nop
    bset    #0, LOCK
    bne     _fail4

    | ---- 5. and with a long gap, so the store has certainly drained -----
    move.b  #0x00, LOCK
    moveq   #48, %d7
1:  subq.l  #1, %d7
    bne     1b
    bset    #0, LOCK
    bne     _fail5
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail5

    | ---- 6. the release side: bclr must report the OLD bit as set -------
    move.b  #0x01, LOCK
    bclr    #0, LOCK
    beq     _fail6
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    bne     _fail6

    | ---- 7. bclr on an already-clear bit reports Z=1 --------------------
    move.b  #0x00, LOCK
    bclr    #0, LOCK
    bne     _fail7

    | ---- 8. the full acquire/release cycle, twice, back to back ---------
    | A lock that can be taken once but not re-taken is just as fatal.
    move.b  #0x00, LOCK
    bset    #0, LOCK
    bne     _fail8
    bclr    #0, LOCK
    beq     _fail8
    bset    #0, LOCK
    bne     _fail8
    bclr    #0, LOCK
    beq     _fail8

    | ---- 9. exactly the patch's shape: interrupts masked, lea first -----
    move.b  #0x00, LOCK
    move.w  %sr, %d1
    ori.w   #0x0700, %sr
    lea     LOCK, %a1
    bset    #0, LOCK
    beq     _got
    move.w  %d1, %sr
    bra     _fail9
_got:
    move.w  %d1, %sr
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail9

    | ---- 10. bset via the address register form, same byte --------------
    move.b  #0x00, (%a1)
    bset    #0, (%a1)
    bne     _fail10
    bclr    #0, (%a1)
    beq     _fail10

    | ---- 11. THE REAL SEQUENCE: an A-line trap immediately before the -----
    | test-and-set.  The 7.5.3 patch is literally
    |     oriw #0x0700,%sr ; lea 0x360,%a1 ; .short 0xa96f ; bset #0,0x360
    | so the lock instruction executes one instruction after an RTE.  If the
    | exception return disturbs the flag state the bit operation then writes,
    | the acquire verdict is wrong and the lock wedges set with no owner.
    move.l  #_aline, 0x28              | vector 10, line-1010 emulator
    move.b  #0x00, LOCK
    ori.w   #0x0700, %sr
    lea     LOCK, %a1
    .short  0xa96f
    bset    #0, LOCK
    bne     _fail11                    | Z must be set: the bit WAS clear
    move.b  LOCK, %d0
    and.b   #0x01, %d0
    cmp.b   #0x01, %d0
    bne     _fail11

    | ---- 12. and the contended case through the same trap path ----------
    move.b  #0x01, LOCK
    lea     LOCK, %a1
    .short  0xa96f
    bset    #0, LOCK
    beq     _fail12                    | Z must be clear: the bit was SET
    andi.w  #0xF8FF, %sr

_pass:
    lea     0xFFFF0000, %a6
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a6)
_halt:
    bra     _halt

_aline:
    | A line-1010 exception stacks the address OF the trapping instruction
    | (format $0: SR at 0(sp), PC at 2(sp)), so a bare RTE would re-execute
    | the trap word forever.  Step the stacked PC past the 2-byte opword,
    | which is what a real A-trap dispatcher does after servicing it.
    addq.l  #2, 2(%sp)
    rte

_fail1:
    move.l  #0xDEAD0001, %d7
    bra     _fail
_fail2:
    move.l  #0xDEAD0002, %d7
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
    bra     _fail
_fail11:
    move.l  #0xDEAD000B, %d7
    bra     _fail
_fail12:
    move.l  #0xDEAD000C, %d7
    bra     _fail
_fail:
    lea     0xFFFF0000, %a6
    move.l  %d7, (%a6)
_fail_halt:
    bra     _fail_halt
