| tb_cas_v2.s — B8 V2 CAS family coverage.
|
| Exercises every legacy CAS EA shape now routed through the V2
| `v2_cas_fire` override block in decode.v.  Each cell maps to a
| distinct case in the override:
|   - (An)           (4 phase: LOAD / CMP / STORE / MOV_MERGE)
|   - (An)+          (5 phase + ALU_ADD An update)
|   - -(An)          (5 phase: SUB An first, then LOAD / CMP / STORE / MOV_MERGE)
|   - (d16,An)       (4 phase, ext2 = disp16, len=6)
|   - (xxx).W        (4 phase, ext2 = abs.W,  len=6, is_abs)
|   - (xxx).L        (4 phase, ext2:ext3 = abs.L, len=8, is_abs)
|
| Match path (Dc == loaded): Z=1, Du stored, Dc unchanged.
| Mismatch path (Dc != loaded): Z=0, Dc := loaded (size-truncated;
|   our uniprocessor relax stores Du unconditionally — Dc update via
|   ALU_MOV_MERGE preserves the upper bits).
|
| Sizes:
|   .B  op[11:9]=101  size=byte
|   .W  op[11:9]=110  size=word
|   .L  op[11:9]=111  size=long
|
| PASS = store 0xC0FFEE00 to 0xFFFF0000.

    .text
    .org 0

_start:
    | ── Test 1: CAS.L (An) — match path ────────────────────────────
    lea     0x70000, %a0
    move.l  #0x11223344, (%a0)
    move.l  #0x11223344, %d1            | Dc
    move.l  #0xAABBCCDD, %d2            | Du
    cas.l   %d1, %d2, (%a0)
    beq     _t1_ok
    bra     _fail
_t1_ok:
    move.l  (%a0), %d0
    cmp.l   #0xAABBCCDD, %d0
    bne     _fail

    | ── Test 2: CAS.W (An)+ — match, A1 += 2 ───────────────────────
    lea     0x70100, %a1
    move.w  #0x1234, (%a1)
    move.l  #0xFFFF1234, %d1
    move.l  #0xDEADABCD, %d2
    cas.w   %d1, %d2, (%a1)+
    beq     _t2_ok
    bra     _fail
_t2_ok:
    move.l  %a1, %d0
    cmpi.l  #0x70102, %d0                | post-inc by 2 (.W)
    bne     _fail
    move.w  -(%a1), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0xABCD, %d0
    bne     _fail

    | ── Test 3: CAS.B -(An) — predec by 1 (non-A7) ──────────────────
    lea     0x70200, %a2
    move.b  #0x55, -1(%a2)
    | Set up a2 to point one past so predec lands on the byte
    | (no, just use a3 = 0x70201 so -(a3) lands on 0x70200)
    lea     0x70201, %a3
    move.b  #0x55, -1(%a3)
    move.l  #0xAA000055, %d1
    move.l  #0xBB000077, %d2
    cas.b   %d1, %d2, -(%a3)
    beq     _t3_ok
    bra     _fail
_t3_ok:
    move.l  %a3, %d0
    cmpi.l  #0x70200, %d0
    bne     _fail
    move.b  (%a3), %d0
    andi.l  #0xFF, %d0
    cmpi.l  #0x77, %d0
    bne     _fail

    | ── Test 4: CAS.L (d16,An) — disp=+0x40 ─────────────────────────
    lea     0x70300, %a4
    move.l  #0xCAFEBABE, 0x40(%a4)
    move.l  #0xCAFEBABE, %d1
    move.l  #0x12345678, %d2
    cas.l   %d1, %d2, 0x40(%a4)
    beq     _t4_ok
    bra     _fail
_t4_ok:
    move.l  0x40(%a4), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | ── Test 5: CAS.W (xxx).W — abs.W ───────────────────────────────
    lea     0x00007E00, %a5
    move.w  #0x9999, (%a5)
    move.l  #0x00009999, %d1
    move.l  #0x0000AAAA, %d2
    cas.w   %d1, %d2, 0x7E00.w
    beq     _t5_ok
    bra     _fail
_t5_ok:
    move.w  (%a5), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0xAAAA, %d0
    bne     _fail

    | ── Test 6: CAS.L (xxx).L — full 32-bit abs ─────────────────────
    lea     0x00070500, %a0
    move.l  #0xDEADBEEF, (%a0)
    move.l  #0xDEADBEEF, %d1
    move.l  #0xFEEDF00D, %d2
    cas.l   %d1, %d2, 0x00070500
    beq     _t6_ok
    bra     _fail
_t6_ok:
    move.l  (%a0), %d0
    cmp.l   #0xFEEDF00D, %d0
    bne     _fail

    | ── Test 7: CAS.B mismatch — Dc[7:0] gets loaded byte ───────────
    lea     0x70600, %a1
    move.b  #0x99, (%a1)
    move.l  #0xAABBCC42, %d1            | Dc != loaded
    move.l  #0x11223377, %d2
    cas.b   %d1, %d2, (%a1)
    bne     _t7_ok
    bra     _fail
_t7_ok:
    | After mismatch, D1 low byte = 0x99 (loaded), upper 24 bits = 0xAABBCC.
    cmp.l   #0xAABBCC99, %d1
    bne     _fail

    | ── Test 8: CAS.W (An) — second size sanity ─────────────────────
    lea     0x70700, %a2
    move.w  #0x4242, (%a2)
    move.l  #0x00004242, %d1
    move.l  #0x0000C0DE, %d2
    cas.w   %d1, %d2, (%a2)
    beq     _t8_ok
    bra     _fail
_t8_ok:
    move.w  (%a2), %d0
    andi.l  #0xFFFF, %d0
    cmpi.l  #0xC0DE, %d0
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
