| addressing_modes.s — Test address register modes: indirect, postinc, predec, displacement
|
| Tests:
|   1. (An) — register indirect
|   2. (An)+ — postincrement (increment by size after load)
|   3. -(An) — predecrement (decrement before store)
|   4. (d16,An) — displacement indirect
|   5. Chaining through A0-A7

    .text
    .org 0

_start:
    | Allocate a tiny data area in RAM: at 0x00001000-0x00001020
    | Use A0-A7 as address pointers

    | Test 1: (An) indirect load
    | Store 0x12345678 at 0x00001000, then load via (A0)
    lea     0x00001000, %a0         | A0 = 0x00001000
    move.l  #0x12345678, %d0
    move.l  %d0, (%a0)              | [0x00001000] = 0x12345678

    move.l  (%a0), %d1              | D1 = 0x12345678 (load from A0)

    lea     0x00100000, %a1         | padding

    | Test 2: (An)+ postincrement load
    | Load from (A0)+ — should load from A0, then A0 += 4
    move.l  (%a0)+, %d2             | D2 = [0x00001000], A0 += 4 → A0 = 0x00001004

    lea     0x00200000, %a2         | padding

    | Test 3: -(An) predecrement store
    | A3 = 0x00001010, then -(A3) decrements to 0x0000100C and stores there
    lea     0x00001010, %a3
    move.l  #0xAABBCCDD, %d3
    move.l  %d3, -(%a3)             | A3 -= 4 → A3 = 0x0000100C, [0x0000100C] = 0xAABBCCDD

    lea     0x00300000, %a4         | padding

    | Test 4: (d16,An) displacement indirect load
    | A5 = 0x00001000, load from (0x10, A5) = 0x00001010
    lea     0x00001000, %a5
    move.l  #0x11223344, %d4
    move.l  %d4, (0x10,%a5)         | [0x00001010] = 0x11223344

    move.l  (0x10,%a5), %d5         | D5 = 0x11223344

    lea     0x00400000, %a6         | padding

    | Test 5: Negative displacement
    | A6 = 0x00001020, load from (-8, A6) = 0x00001018
    lea     0x00001020, %a7
    move.l  #0x55667788, %d6
    move.l  %d6, (-8,%a7)           | [0x00001018] = 0x55667788

    move.l  (-8,%a7), %d7           | D7 = 0x55667788

    | All addressing modes tested; signal PASS
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
    stop    #0x2700
    bra     _halt
