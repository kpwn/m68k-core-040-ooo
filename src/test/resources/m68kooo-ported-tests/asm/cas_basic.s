| cas_basic.s — Test for CAS.B / CAS.W / CAS.L memory forms.
|
| CAS semantics (PRM §4.37):
|   - LOAD[size] <ea>.  Compare vs Dc.
|   - If match (Z=1 after subtract): STORE Du → <ea>; Dc unchanged.
|   - If mismatch (Z=0): Dc := loaded (size-truncated); memory unchanged.
|
| NOTE: this core implements the match path strictly, and the mismatch
| path relaxes "memory unchanged" to "memory rewritten with loaded
| value" (idempotent on match, arch-invisible in a uniprocessor).  All
| observable state (Dc low bits, CCR) matches the PRM.
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
    |   Expect: Z=0, D1 := 0xCAFEBABE (full long), memory unchanged by arch
    |   view (we accept the simplified "memory gets rewritten with loaded
    |   value" relaxation so the arch-visible contents match).
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
    |   24 bits preserved.
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
