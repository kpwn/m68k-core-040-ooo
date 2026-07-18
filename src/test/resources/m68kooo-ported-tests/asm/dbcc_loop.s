| dbcc_loop.s — DBcc (decrement and branch on condition)
|
| NOTE: DBcc is NOT yet implemented in decode.v.
| This test is a placeholder for when it is.
|
| DBcc decrements a counter register and branches if the condition is false.
| Format: DBcc Dn, label
| Semantics:
|   if (condition false) {
|       Dn--;
|       if (Dn != -1) branch to label
|       else fall through
|   } else {
|       fall through
|   }
|
| This is crucial for Mac OS loops (especially DBRA, which branches on Z≠0).

    .text
    .org 0

_start:
    | Test 1: DBRA — loop counter using D0
    | DBRA Dn, label is equivalent to: decrement Dn, if Dn ≠ -1, branch
    | Useful for counting down from N to 0.
    | We'll count down from 4 to 0 and accumulate a sum in D1.

    move.l  #0x00000004, %d0        | counter = 4
    move.l  #0x00000000, %d1        | sum = 0

_loop1:
    add.l   %d0, %d1                | sum += counter
    dbra    %d0, _loop1             | D0--, if D0 != -1 goto _loop1
                                    | Final: D1 = 4 + 3 + 2 + 1 + 0 = 10 (0x0A)

    lea     0x00100000, %a0

    | Test 2: DBEQ — decrement and branch if Z=0 (not equal)
    | Similar loop structure, but conditional on Z flag
    | Set up: D2 = counter (3), D3 = sum (0)

    move.l  #0x00000003, %d2        | counter = 3
    move.l  #0x00000000, %d3        | sum = 0

_loop2:
    tst.l   %d2                     | Z flag set based on D2
    add.l   %d2, %d3                | sum += counter (unconditional)
    dbeq    %d2, _loop2             | D2--, if (Z was set) branch
                                    | This should exit after D2 becomes 0

    lea     0x00200000, %a1

    | Test 3: DBNE — decrement and branch if Z=1 (equal)
    | D4 = counter (2), D5 = sum (0)

    move.l  #0x00000002, %d4
    move.l  #0x00000000, %d5

_loop3:
    tst.l   %d4
    add.l   %d4, %d5                | sum += counter
    dbne    %d4, _loop3             | D4--, if (Z was clear) branch

    lea     0x00300000, %a2

    | Test 4: DBPL — decrement and branch if N=0 (plus)
    | D6 = counter (5), D7 = sum (0)

    move.l  #0x00000005, %d6
    move.l  #0x00000000, %d7

_loop4:
    tst.l   %d6
    add.l   %d6, %d7                | sum += counter
    dbpl    %d6, _loop4             | D6--, if (N was clear) branch

    lea     0x00400000, %a3

    | All DBcc tests done; signal PASS
    lea     0xFFFF0000, %a4
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a4)              | PASS

_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a4
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a4)
    stop    #0x2700
    bra     _halt
