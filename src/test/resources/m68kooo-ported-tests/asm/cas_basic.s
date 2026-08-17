| cas_basic.s — Test for CAS.B / CAS.W / CAS.L memory forms.
|
| CAS semantics (PRM §4.37):
|   - LOAD[size] <ea>.  Compare vs Dc.
|   - If match (Z=1 after subtract): STORE Du → <ea>; Dc unchanged.
|   - If mismatch (Z=0): Dc := loaded (size-truncated); memory unchanged.
|
| NOTE (corrected 2026-08-09): this header used to claim the mismatch
| path "relaxes 'memory unchanged' to 'memory rewritten with loaded
| value' (idempotent on match, arch-invisible in a uniprocessor)".  That
| description was wrong on its own terms and hid a real data-corruption
| bug.  The store the decoder actually emitted was unpredicated and took
| its data from **Du**, not from the loaded value, so a failed compare
| overwrote the cell with Du — arch-VISIBLE to the very next load, in
| the exact lock-free retry idiom CAS exists for.  Measured on the
| pre-fix RTL: memory at the CAS target read back 0x55555555 (= Du)
| instead of 0xCAFEBABE.
|
| It survived because Tests 2 and 5 below — the only mismatch cases —
| checked Dc and the CCR and never looked at memory at all.  They do now
| (the original Dc/CCR assertions are kept and added to, not replaced),
| and cas_mismatch_memory.s covers the mismatch path in depth.
|
| Both the CAS mismatch and CAS2 behaviour are now architecturally exact:
| memory is left untouched, and the LSU suppresses the store outright
| rather than rewriting the same bytes.
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS.L match.  mem[0x50000] = 0x11223344, Dc=D1=0x11223344,
    |   Du=D2=0xAABBCCDD.  Expect: Z=1, mem stays 0xAABBCCDD, D1 unchanged.
    lea     0x50000, %a0
    move.l  #0x11223344, (%a0)
    move.l  #0x11223344, %d1
    move.l  #0xAABBCCDD, %d2
    cas.l   %d1, %d2, (%a0)
    beq     _t1_ok
    bra     _fail
_t1_ok:
    move.l  #0xAABBCCDD, %d0
    cmp.l   (%a0), %d0
    bne     _fail
    move.l  #0x11223344, %d0
    cmp.l   %d0, %d1
    bne     _fail

    | ── Test 2: CAS.L mismatch.  mem[0x50010] = 0xCAFEBABE, Dc=D1=0x11223344.
    |   Expect: Z=0, D1 := 0xCAFEBABE (full long), and memory UNCHANGED —
    |   Du (0x55555555) must never reach the cell.
    lea     0x50010, %a1
    move.l  #0xCAFEBABE, (%a1)
    move.l  #0x11223344, %d1
    move.l  #0x55555555, %d2
    cas.l   %d1, %d2, (%a1)
    bne     _t2_ok
    bra     _fail
_t2_ok:
    move.l  #0xCAFEBABE, %d0
    cmp.l   %d0, %d1                  | D1 must be the loaded value
    bne     _fail
    move.l  (%a1), %d0                | ...and memory must be untouched
    move.l  #0xCAFEBABE, %d3
    cmp.l   %d3, %d0
    bne     _fail

    | ── Test 3: CAS.W match.  mem[0x50020] = 0x1234, Dc low word = 0x1234.
    |   Expect: Z=1, mem[0x50020] = 0xABCD.
    lea     0x50020, %a2
    move.w  #0x1234, (%a2)
    move.l  #0xFFFF1234, %d1          | Dc low word = 0x1234, upper bits preserved
    move.l  #0xDEADABCD, %d2          | Du low word = 0xABCD
    cas.w   %d1, %d2, (%a2)
    beq     _t3_ok
    bra     _fail
_t3_ok:
    move.w  (%a2), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0xABCD, %d0
    bne     _fail

    | ── Test 4: CAS.B match.  mem[0x50030] = 0x42, Dc low byte = 0x42.
    |   Expect: Z=1, mem[0x50030] = 0x55.
    lea     0x50030, %a3
    move.b  #0x42, (%a3)
    move.l  #0xAA000042, %d1          | Dc low byte = 0x42
    move.l  #0xBB000055, %d2          | Du low byte = 0x55
    cas.b   %d1, %d2, (%a3)
    beq     _t4_ok
    bra     _fail
_t4_ok:
    move.b  (%a3), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x55, %d0
    bne     _fail

    | ── Test 5: CAS.B mismatch — Dc[7:0] gets the loaded byte, Dc upper
    |   24 bits preserved, and the memory byte is UNCHANGED (Du's low
    |   byte 0x77 must not appear).
    lea     0x50040, %a4
    move.b  #0x99, (%a4)
    move.l  #0xAABBCC42, %d1
    move.l  #0x11223377, %d2
    cas.b   %d1, %d2, (%a4)
    bne     _t5_ok
    bra     _fail
_t5_ok:
    | D1 low byte should be 0x99, upper 24 bits = 0xAABBCC
    move.l  #0xAABBCC99, %d0
    cmp.l   %d0, %d1
    bne     _fail
    move.b  (%a4), %d0                | ...and the memory byte stays 0x99
    andi.l  #0xFF, %d0
    cmpi.l  #0x99, %d0
    bne     _fail

    | ── PASS ─────────────────────────────────────────────────────────
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt
