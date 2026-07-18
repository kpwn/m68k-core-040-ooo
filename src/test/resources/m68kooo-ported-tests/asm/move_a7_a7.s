| move_a7_a7.s — MOVE.L (A7),A7 (Mac stack-frame unwind idiom).
|
| Q700 HW symptom (2026-05-20): boot reaches the ADB busy-wait at
| 0x4080a8e6, level-1 VBL IRQs fire, eventually one handler RTSes
| through the routine at ROM 0x408855e4:
|
|     408855e4:  2e 57   move.l (%a7), %a7
|     408855e6:  4e 75   rts
|
| Observed via JTAG break-PC: the CPU executes 0x408855e4 (MOVE.L
| (A7),A7) and instead of falling through to 0x408855e6 (RTS), it
| jumps to mem[A7]'s ADDRESS — i.e. PC ends up == old A7.  Looks
| like MOVE.L (A7),A7 is being executed as JMP (A7).
|
| Expected behavior (m68k PRM §4.55):
|   1. Read 32 bits from mem[A7] → temp
|   2. A7 := temp
|   3. PC := PC + 2 (next sequential)
|
| PASS sentinel: 0xC0FFEE00.
| FAIL sentinels:
|   0xDEAD0007  — A7 not updated after MOVE.L (A7),A7
|   0xDEAD0008  — PC jumped wrong (would manifest as the test
|                 never reaching the cmp at all, but is unreachable
|                 directly; the JTAG bug pattern would land us in
|                 RAM and bus-error/illegal before we get back)
|   0xDEAD0004  — vec-4 illegal-instruction trap fired (caught by
|                 the illegal vector below)
|   0xDEAD0002  — vec-2 bus-error trap fired

    .text
    .org 0

_start:
    | Stack at high RAM, sentinel byte pad below so a wild-jump
    | down-stack into "data" lands in a fail-trap region.
    lea     0x00010000, %a7

    | Install fail-trap handlers BEFORE running the candidate
    | instruction so a wild jump is observed cleanly.
    move.l  #_buserr,  0x00000008    | vec 2  (bus error)
    move.l  #_illegal, 0x00000010    | vec 4  (illegal instruction)
    move.l  #_addrerr, 0x0000000c    | vec 3  (address error)

    | Pre-populate the longword the MOVE.L will load.
    | A7 = 0x10000.  mem[A7] = 0x00012345 (a "stack-cleanup pointer"
    | the Mac unwind idiom would normally have).
    move.l  #0x00012345, (%a7)

    | Capture pre-instruction A7 in D0 (= the value we expect PC
    | NOT to land on — the bug pattern has PC := old A7).
    move.l  %a7, %d0                  | D0 = 0x10000 (expected = OK)

    | --- THE INSTRUCTION UNDER TEST ----------------------------
    move.l  (%a7), %a7                | A7 := mem[A7] = 0x00012345
    | --- if we get here, MOVE.L returned (not as JMP) -----------

    | Verify A7 actually updated.
    cmp.l   #0x00012345, %a7
    bne     _fail_a7_unchanged

    | Pass — restore A7 to a safe place so the PASS write works.
    lea     0x00010000, %a7
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_a7_unchanged:
    lea     0x00010000, %a7
    move.l  #0xDEAD0007, 0xFFFF0000
    bra     .

_buserr:
    lea     0x00010000, %a7
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .

_addrerr:
    lea     0x00010000, %a7
    move.l  #0xDEAD0003, 0xFFFF0000
    bra     .

_illegal:
    lea     0x00010000, %a7
    move.l  #0xDEAD0004, 0xFFFF0000
    bra     .
