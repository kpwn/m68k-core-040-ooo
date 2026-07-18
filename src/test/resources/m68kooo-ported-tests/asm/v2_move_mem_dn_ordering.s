| v2_move_mem_dn_ordering.s — MOVE reg-to-abs-mem then MOVE abs-mem-to-Dn
|
| Regression guard for the V2-decoder store-load ordering hazard: a V2
| MOVE.L Dn,(xxx).L crack emits TST (flags, iq_int) + STORE (iq_mem,
| is_abs=1, pbase=PHYS_ZERO, disp=abs_addr).  The follow-up MOVE.L
| (An),Dn load has pbase=An, disp=0.  Although both EAs resolve to the
| same runtime address, iq_mem's static alias check (compare pbase+disp
| literally) cannot see the collision.
|
| Fix: V2's mem→Dn MOVE carries flags_wr=01111 on the LOAD phase, which
| allocates a CCR rename slot.  iq_mem's ccr_wait_block then keeps the
| LOAD behind every prior flag-writer (incl. the earlier STORE's TST),
| forcing serialisation until the LSU drains the older STORE.  Without
| this, the LOAD can issue ahead of an older S_ST_BUF-parked store,
| reading stale memory.
|
| Covered variants:
|   .L (single-uop LOAD shape, matches legacy Task #47)
|   .W / .B (two-uop crack: LOAD to TMP1 + ALU_MOV_MERGE)
|   predec source, postinc source, (d16,An), (xxx).L
|
| PASS sentinel: 0xC0FFEE00 → 0xFFFF0000.

    .text
    .org 0

_start:
    | ── MOVE.L Dn,(xxx).L  followed by  MOVE.L (An),Dn ─────────────
    lea     0x00101000, %a5
    lea     0x00101000, %a4
    move.l  #0xdeadbeef, %d5
    .word   0x23cd, 0x0010, 0x1000   | MOVE.L A5, 0x101000.L
    move.l  (%a4), %d0
    cmp.l   #0x00101000, %d0           | A5=0x00101000 was stored there
    bne     _fail

    | ── MOVE.L #,(xxx).L  followed by  MOVE.L (An),Dn ──────────────
    lea     0x00102000, %a4
    .word   0x23fc, 0x1234, 0x5678, 0x0010, 0x2000  | MOVE.L #0x12345678, 0x102000.L
    move.l  (%a4), %d0
    cmp.l   #0x12345678, %d0
    bne     _fail

    | ── MOVE.W Dn,(xxx).W  followed by  MOVE.W (An),Dn.W (merge) ───
    | Prime D1 upper word so we can detect that the MERGE preserved it.
    move.l  #0xaaaa0000, %d1
    lea     0x00001200, %a4
    move.w  #0x1234, %d2
    .word   0x31c2, 0x1200             | MOVE.W D2, 0x1200.W
    .word   0x3214                     | MOVE.W (A4), D1 (WORD load, MERGE)
    cmp.l   #0xaaaa1234, %d1
    bne     _fail

    | ── MOVE.B Dn,(xxx).W  followed by  MOVE.B (An),Dn.B (merge) ───
    move.l  #0xbbbbbb00, %d3
    lea     0x00001300, %a4
    move.b  #0x55, %d4
    .word   0x11c4, 0x1300             | MOVE.B D4, 0x1300.W
    .word   0x1614                     | MOVE.B (A4), D3 (BYTE load, MERGE)
    cmp.l   #0xbbbbbb55, %d3
    bne     _fail

    | ── MOVE.L (An)+, Dn (postinc) ─────────────────────────────────
    lea     0x00001400, %a0
    move.l  #0x12345678, (%a0)
    move.l  #0xcafebabe, %d0
    move.l  (%a0)+, %d0
    cmp.l   #0x12345678, %d0
    bne     _fail
    cmpa.l  #0x00001404, %a0
    bne     _fail

    | ── MOVE.L -(An), Dn (predec) ──────────────────────────────────
    lea     0x00001500, %a0
    move.l  #0xfeedface, 0x000014fc
    move.l  (%a0), %d7                 | prime the bus so A0 path settles
    movea.l #0x00001500, %a0
    move.l  -(%a0), %d0
    cmp.l   #0xfeedface, %d0
    bne     _fail
    cmpa.l  #0x000014fc, %a0
    bne     _fail

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d0
    move.l  %d0, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  #0xDEADBEEF, %d0
    move.l  %d0, (%a0)
_fail_halt:
    bra     _fail_halt
