| mem_back_to_back.s — Memory: consecutive loads, stores, and aliasing
|
| Tests:
|   1. Back-to-back loads from different addresses
|   2. Back-to-back stores to different addresses
|   3. Load after store to same address (forward value)
|   4. Store-load aliasing detection

    .text
    .org 0

_start:
    | Data area: 0x00001000-0x00001040

    | Test 1: Back-to-back loads
    | Store two values, then load both back-to-back
    lea     0x00001000, %a0
    move.l  #0x11111111, %d0
    move.l  %d0, (%a0)              | [0x00001000] = 0x11111111

    lea     0x00001004, %a1
    move.l  #0x22222222, %d1
    move.l  %d1, (%a1)              | [0x00001004] = 0x22222222

    lea     0x00100000, %a2         | padding

    | Now load them back-to-back
    move.l  (%a0), %d2              | D2 = 0x11111111 (load from 0x1000)
    move.l  (%a1), %d3              | D3 = 0x22222222 (load from 0x1004)

    lea     0x00200000, %a3

    | Test 2: Back-to-back stores to different addresses
    lea     0x00001010, %a4
    lea     0x00001014, %a5

    move.l  #0x33333333, %d4
    move.l  %d4, (%a4)              | [0x00001010] = 0x33333333

    move.l  #0x44444444, %d5
    move.l  %d5, (%a5)              | [0x00001014] = 0x44444444

    lea     0x00300000, %a6

    | Test 3: Load-after-store to same address (should get new value)
    | Store 0xAAAAAAAA to 0x00001020
    lea     0x00001020, %a0
    move.l  #0xAAAAAAAA, %d6
    move.l  %d6, (%a0)              | [0x00001020] = 0xAAAAAAAA

    | Immediately load from same address
    move.l  (%a0), %d7              | D7 = 0xAAAAAAAA (should read the value we just wrote)

    lea     0x00400000, %a1

    | Test 4: Store-load to overlapping addresses
    | Store 64-bit value across 0x1030-0x1038
    lea     0x00001030, %a2
    move.l  #0xBBBBBBBB, %d0
    move.l  %d0, (%a2)              | [0x00001030] = 0xBBBBBBBB

    move.l  #0xCCCCCCCC, %d1
    move.l  %d1, (4,%a2)            | [0x00001034] = 0xCCCCCCCC

    lea     0x00500000, %a3

    | Reload them
    move.l  (%a2), %d2              | D2 = 0xBBBBBBBB
    move.l  (4,%a2), %d3            | D3 = 0xCCCCCCCC

    lea     0x00600000, %a4

    | All memory tests done; signal PASS
    lea     0xFFFF0000, %a5
    move.l  #0xC0FFEE00, %d4
    move.l  %d4, (%a5)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a5
    move.l  #0xDEADBEEF, %d4
    move.l  %d4, (%a5)
    stop    #0x2700
    bra     _halt
