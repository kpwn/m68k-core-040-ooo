| fpu_fmovem_x_slot1_entry.s — FMOVEM.X must not depend on its ALIGNMENT.
|
| The decode FSM that owns FMOVEM.X originally inspected SLOT 0 of a fetch group
| only. A slot1 FMOVEM.X fell through to the microcode engine's FP_MEM_TRAP_ENTRY
| and took a vector-11 F-line — so the SAME instruction executed or trapped purely
| according to what preceded it. Task #241 recorded that as "a known, bounded,
| NON-regressing scope limit"; it is not bounded, because alignment is not a
| property the programmer controls.
|
| It matters here specifically: `fmovemx (%a0),%fp0` is, per
| fpu_fmovem_x_an_indirect.s, "the single most common FMOVEM.X EA in the Q700
| universal ROM (~93 static-list sites)", and a live vec-11 was confirmed on FPGA
| at ROM 0x40891196 for that exact opword pair (F210 D080). Every such trap enters
| the ROM FPSP — which itself uses FMOVEM.X — so the fallback faults inside the
| handler for the fault.
|
| This test runs the IDENTICAL load at several different alignments. Before the
| slot1-entry fix the odd ones took vector 11; all of them must now execute and
| deliver the same value.
|
| Sentinel: 0xC0FFEE00 pass. 0xF5xx = alignment marker xx trapped (low nibble =
| the vector). 0xDEADD0nn = alignment nn executed but delivered the wrong value.

    .text
    .org 0
    .equ PASS_SENT, 0xFFFF0000
    .equ VBASE,     0x00100000
    .equ SRC,       0x00020000

_start:
    lea     0x00010000, %a7
    move.l  #VBASE, %d0
    movec   %d0, %vbr
    move.l  #_trapB, VBASE+0x2C        | 11 line-1111
    move.l  #_trap4, VBASE+0x10        | 4  illegal
    move.l  #_trap2, VBASE+0x08        | 2  bus error
    move.l  #_trap3, VBASE+0x0C        | 3  address error

    | Seed 1.0 in Extended: sign 0, exponent 0x3FFF, mantissa 1<<63.
    lea     SRC, %a0
    move.l  #0x3FFF0000, (%a0)
    move.l  #0x80000000, 4(%a0)
    move.l  #0x00000000, 8(%a0)
    lea     0x00020100, %a1

    | ── alignment 0: no filler ──────────────────────────────────────────
    move.l  #0xF5000000, %d2
    fmovem.x %a0@, %fp0
    bsr     _check

    | ── alignment 1: one word of filler ─────────────────────────────────
    move.l  #0xF5010000, %d2
    nop
    fmovem.x %a0@, %fp0
    bsr     _check

    | ── alignment 2: two words ──────────────────────────────────────────
    move.l  #0xF5020000, %d2
    nop
    nop
    fmovem.x %a0@, %fp0
    bsr     _check

    | ── alignment 3: three words ────────────────────────────────────────
    move.l  #0xF5030000, %d2
    nop
    nop
    nop
    fmovem.x %a0@, %fp0
    bsr     _check

_pass:
    lea     PASS_SENT, %a1
    move.l  #0xC0FFEE00, %d2
    move.l  %d2, (%a1)
_halt:
    bra     _halt

| Store FP0 back and compare against the seed. Uses a slot-0-safe store form.
_check:
    | Read FP0 back with the SCALAR FMOVE.X store, NOT fmovem.x: the FMOVEM.X STORE
    | direction (opclass 111) is still out of scope and traps, which would make every
    | failure here ambiguous between the load under test and the checker itself.
    lea     0x00020100, %a1
    fmove.x %fp0, %a1@
    move.l  (%a1), %d4
    cmp.l   #0x3FFF0000, %d4
    bne     _badval
    move.l  4(%a1), %d4
    cmp.l   #0x80000000, %d4
    bne     _badval
    rts

_badval:
    and.l   #0x00FF0000, %d2
    lsr.l   #8, %d2
    lsr.l   #8, %d2
    or.l    #0xDEADD000, %d2
    bra     _report

_trapB:
    or.l    #0x0B, %d2
    bra     _report
_trap4:
    or.l    #0x04, %d2
    bra     _report
_trap2:
    or.l    #0x02, %d2
    bra     _report
_trap3:
    or.l    #0x03, %d2
_report:
    lea     PASS_SENT, %a1
    move.l  %d2, (%a1)
_halt_f:
    bra     _halt_f
