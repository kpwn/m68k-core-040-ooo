| a7_word_then_long_postinc_pop.s — a WORD (A7)+ pop immediately followed by a
| LONG (A7)+ pop must read from the POST-increment address.
|
| Caught on hardware 2026-09-10 as the FIRST fault of a 7.5.3 boot collapse.
| The Q700 ROM at 0x40827FE0 does exactly this:
|
|     40827fe0: moveal %sp@+,%a1     pop 4
|     40827fe2: movew  %sp@+,%d0     pop 2      <- leaves A7 at 2-mod-4
|     40827fe4: moveal %sp@+,%a0     pop 4      <- the load under test
|     40827fe8: moveal %a0@,%a0      dereference -> BUS ERROR
|
| The board halted at 0x40827FE8 with A0 = 0x26FA4080 and A7 = 0x007C56B2, so
| the long pop read from 0x007C56AE. 0x26FA4080 is the expected 0x4080xxxx
| value SHIFTED DOWN BY TWO BYTES -- i.e. the long read used an A7 that had not
| yet taken the word pop's +2, reading two bytes early.
|
| Consequences on the board: A0 garbage -> bus error -> the ROM's handler faults
| in turn -> exception frames march A7 down through low memory and destroy the
| VECTOR TABLE (captured: A7=0x18, vectors 25/26 garbage). Every "random illegal
| instruction / wild PC" boot failure is that one collapse.
|
| STATUS: this test PASSES on the current core. It does NOT yet reproduce the
| hardware fault, so the plain pop sequence is correct in isolation and the real
| case needs another ingredient -- the exception ring shows vec-0x0A line-A traps
| at 0x000ABBCC/0x000ABBE0 immediately BEFORE the bus error, so an exception taken
| with A7 at 2-mod-4 (pushing/popping a frame that straddles a 16-byte line) is the
| prime suspect. Kept as a fence on the exact ROM sequence.
|
| Sentinels: 0xDEAD7001 A1 wrong, 0xDEAD7002 D0 wrong, 0xDEAD7003 A0 wrong
|            (0xDEAD7004..6 the same at a line-crossing alignment)

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000

_start:
    | ---- case 1: stack 4-byte aligned to start ----------------------------
    lea     0x00011000, %a7
    move.l  #0xA0A0A0A0, -(%sp)         | will be popped into A0
    move.w  #0x1234,     -(%sp)         | will be popped into D0
    move.l  #0xA1A1A1A1, -(%sp)         | will be popped into A1

    movea.l %sp@+, %a1
    move.w  %sp@+, %d0
    movea.l %sp@+, %a0

    move.l  %a1, %d2
    cmp.l   #0xA1A1A1A1, %d2
    bne     _f1
    and.l   #0xFFFF, %d0
    cmp.l   #0x1234, %d0
    bne     _f2
    move.l  %a0, %d2
    cmp.l   #0xA0A0A0A0, %d2
    bne     _f3

    | ---- case 2: the SAME sequence with the long pop landing at line
    |      offset 14, i.e. spanning the 16-byte boundary, which is where the
    |      hardware capture actually sat (0x007C56AE & 0xF == 14) ----------
    | operands placed so the long pop reads from an address ending in 0xC/0xE
    lea     0x00012000, %a7
    move.l  #0xB0B0B0B0, -(%sp)
    move.w  #0x5678,     -(%sp)
    move.l  #0xB1B1B1B1, -(%sp)
    | A7 is now 0x00011FF6; pops read at FF6(long), FFA(word), FFC(long)
    movea.l %sp@+, %a1
    move.w  %sp@+, %d0
    movea.l %sp@+, %a0
    move.l  %a1, %d2
    cmp.l   #0xB1B1B1B1, %d2
    bne     _f4
    and.l   #0xFFFF, %d0
    cmp.l   #0x5678, %d0
    bne     _f5
    move.l  %a0, %d2
    cmp.l   #0xB0B0B0B0, %d2
    bne     _f6

_pass:
    lea     PASS_SENT, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_f1:
    move.l  #0xDEAD7001, %d7
    bra     _fail
_f2:
    move.l  #0xDEAD7002, %d7
    bra     _fail
_f3:
    move.l  #0xDEAD7003, %d7
    bra     _fail
_f4:
    move.l  #0xDEAD7004, %d7
    bra     _fail
_f5:
    move.l  #0xDEAD7005, %d7
    bra     _fail
_f6:
    move.l  #0xDEAD7006, %d7
_fail:
    lea     PASS_SENT, %a0
    move.l  %d7, (%a0)
_halt_f:
    bra     _halt_f
