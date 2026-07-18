| lsu_unaligned_long_load_xline.s — unaligned long load that CROSSES a
| 32-byte cache-line boundary.
|
| Q700 boot symptom: in the inner descriptor-walk loop at PC=0x40881872
| (move.l -(A0), D3 — predec long load), iter K hit A0=0x0017FB7E,
| which reads bytes 0x17FB7E (in line 0x17FB60-7F) and 0x17FB80..0x17FB81
| (in line 0x17FB80-9F).  Sim returned 0x7FFFFC12 instead of the expected
| RAM-test pattern bytes.  Same loop iterations at non-cache-line-crossing
| 2-aligned PAs (e.g. A0=0x17FB82) returned correct data.
|
| Hypothesis: LSU's unaligned long-load split path mishandles
| cache-line-crossing reads, returning stale / wrong bytes from one of
| the two halves.

    .text
    .org 0

_start:
    | Place known data at PA 0x10078..0x10084 spanning a cache line
    | boundary at PA 0x10080.  The 4-byte cache-line-crossing read at
    | PA 0x1007E reads bytes [7E, 7F, 80, 81] = (high) 0xCAFE | (low) 0xBABE
    | into the long.
    |
    | Layout (PA): [10078]=DEAD [1007A]=BEEF [1007C]=CAFE [1007E]=BABE [10080]=1234 [10082]=5678 [10084]=DEAD ...
    | Long at 0x1007E (unaligned, 2-aligned, line-cross) =
    |   high = word at 0x1007E = 0xBABE
    |   low  = word at 0x10080 = 0x1234
    |   long = 0xBABE1234
    |
    | We seed using known unique values so any wrong return is obvious.

    | Stage 1: prepare the data.
    move.l  #0xDEADBEEF, %d0
    move.l  #0xCAFEBABE, %d1
    move.l  #0x12345678, %d2
    move.l  #0xAABBCCDD, %d3
    | Place [10078..1007B] = 0xDEADBEEF (aligned long)
    move.l  %d0, 0x10078
    | Place [1007C..1007F] = 0xCAFEBABE (aligned long)
    move.l  %d1, 0x1007C
    | Place [10080..10083] = 0x12345678 (aligned long, START of next line)
    move.l  %d2, 0x10080
    | Place [10084..10087] = 0xAABBCCDD
    move.l  %d3, 0x10084

    | Stage 2: aligned long read at 0x1007C → expect 0xCAFEBABE.
    move.l  0x1007C, %d4
    cmp.l   #0xCAFEBABE, %d4
    bne     _fail1

    | Stage 3: aligned long read at 0x10080 → expect 0x12345678.
    move.l  0x10080, %d4
    cmp.l   #0x12345678, %d4
    bne     _fail2

    | Stage 4: UNALIGNED long read at 0x1007E (line-cross) → expect 0xBABE1234.
    | This is the exact ROM frontier shape.
    move.l  0x1007E, %d4
    cmp.l   #0xBABE1234, %d4
    bne     _fail3

    | Stage 5: predec form.  Set A0 = 0x10082 then move.l -(A0), D5
    | This makes A0 -> 0x1007E and reads long at 0x1007E.
    lea     0x10082, %a0
    move.l  -(%a0), %d5
    cmp.l   #0xBABE1234, %d5
    bne     _fail4
    cmp.l   #0x1007E, %a0
    bne     _fail4

    | Stage 6: line-crossing unaligned at 0x1009E (next line cross).
    | Setup: [1009C..1009F] = 0x55667788, [100A0..100A3] = 0x99AABBCC.
    move.l  #0x55667788, 0x1009C
    move.l  #0x99AABBCC, 0x100A0
    move.l  0x1009E, %d6
    cmp.l   #0x778899AA, %d6
    bne     _fail5

    | Stage 7: predec into line-crossing at 0x1009E.
    lea     0x100A2, %a1
    move.l  -(%a1), %d7
    cmp.l   #0x778899AA, %d7
    bne     _fail6

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

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

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
