| ras_stale_trampoline_chain.s — chained RTS hops over a drifted RAS
|
| MECHANISM UNDER TEST:
|   `move.l #next,-(%a7) / rts` trampolines are a core Mac OS dispatch
|   idiom (vector dispatch, A-trap glue, patch chains).  Every hop is an
|   RTS that does NOT return where its matching call pushed, so the RAS
|   drifts out of alignment with the real call depth and each hop pops a
|   prediction belonging to some earlier, unrelated frame.  The loaded
|   return address is the architectural truth on every one of them.
|
|   This is the shape behind the HW System 7.5.3 wild jump: an RTS
|   retiring to a leftover RAS entry rather than the address on the
|   stack.
|
| ATTACK:
|   Two nested BSRs that each throw their return address away (so the
|   RAS accumulates TWO stale entries and the architectural call depth
|   is zero), then two chained hand-built RTS hops.  Hop 1 pops the
|   stale _r2, hop 2 pops the stale _r1 — neither has anything to do
|   with the frame being read.
|
| EVERY WRONG OUTCOME IS DISTINGUISHABLE, and no correct landing pad is
| ever the fall-through of the RTS that must reach it:
|   _t2         0xC0FFEE00  PASS
|   _bad_r1     0xDEAD0001  hop 2 committed the stale RAS prediction
|   _bad_r2     0xDEAD0002  hop 1 committed the stale RAS prediction
|   _bad_ft1    0xDEAD0003  hop 1 lost br_taken and fell through
|   _bad_ft2    0xDEAD0004  hop 2 lost br_taken and fell through
|
| SENSITIVITY (verified, not assumed): with commit.v's mispredict
|   compare tampered to skip UOP_LOAD entries this test goes
|   [PASS] -> [FAIL] and commit.v's CFI monitor prints a violation.

    .text
    .org 0

_start:
    lea     0x00020000, %a7

    bsr     _u1
_r1:
    bra     _bad_r1                 | RAS entry #1 (never architecturally used)

_u1:
    addq.l  #4, %a7                 | discard the return address
    bsr     _u2
_r2:
    bra     _bad_r2                 | RAS entry #2 (never architecturally used)

_u2:
    addq.l  #4, %a7                 | discard the return address
    | RAS now holds [_r1, _r2]; architectural call depth is 0.
    move.l  #_t1, -(%a7)
    rts                             | hop 1: RAS says _r2, memory says _t1
_ft1:
    bra     _bad_ft1

_t1:
    move.l  #_t2, -(%a7)
    rts                             | hop 2: RAS says _r1 (or empty), memory
                                    | says _t2
_ft2:
    bra     _bad_ft2

_t2:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_hang_ok:
    bra     _hang_ok

_bad_r1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0001, %d0
    move.l  %d0, (%a0)
_h1:
    bra     _h1

_bad_r2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0002, %d0
    move.l  %d0, (%a0)
_h2:
    bra     _h2

_bad_ft1:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0003, %d0
    move.l  %d0, (%a0)
_h3:
    bra     _h3

_bad_ft2:
    lea     0xFFFF0000, %a0
    move.l  #0xDEAD0004, %d0
    move.l  %d0, (%a0)
_h4:
    bra     _h4
