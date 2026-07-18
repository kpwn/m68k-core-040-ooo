| bsr_recursive_rts.s — Recursive BSR → RTS stack unwind test.
|
| Validates the V2 BSR crack + RTS migration end-to-end: a small
| recursive factorial(4) = 24 computation via repeated BSR/RTS pairs
| exercises the push/pop discipline on the stack across multiple
| nesting levels, and confirms that each RTS pops the correct
| return address.
|
| fact(n): if (n <= 1) return 1; else return n * fact(n-1)
|
| Expected: D0 = fact(4) = 24 at exit.

    .text
    .org 0

_start:
    lea     0x00010000, %a7           | stack at 0x00010000 (grows down)
    move.l  #4, %d0                   | argument in D0
    bsr     _fact
    | D0 = fact(4) = 24
    cmp.l   #24, %d0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    stop    #0x2700
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt_fail:
    stop    #0x2700
    bra     _halt_fail

    | ── fact(n) in D0 ────────────────────────────────────────────────
    | Preserves D1 (caller).  Standard recursive form: base case
    | returns 1, recursive case computes n * fact(n-1).
    | Save D0 on the stack across the nested call, reload after,
    | multiply.
_fact:
    cmp.l   #1, %d0
    ble     _fact_base
    move.l  %d0, -(%a7)               | push n
    subq.l  #1, %d0                   | n - 1
    bsr     _fact                     | recurse — D0 = fact(n-1)
    move.l  (%a7)+, %d2               | pop n into D2
    muls.l  %d2, %d0                  | D0 = n * fact(n-1)
    rts

_fact_base:
    moveq   #1, %d0
    rts
