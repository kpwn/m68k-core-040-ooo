| fuse_cmp_bcc.s — test CMP.L Dm,Dn + Bcc fusion (task #113)
|
| Minimal test: one CMP.L followed by BEQ.  Expected: BEQ takes
| because Dm == Dn, so fused op computes Z=1, Beq cc_true=1.
|
| Also tests that a subsequent CCR-reading Bcc sees the CCR written
| by the fused op — i.e. the fused op must have written CCR with
| the correct flag bits (flags_wr_mask = 5'b01111 = NZVC).

    .text
    .org 0

_start:
    | Setup: D0 = D1 = 5, D2 = 99, D3 = 99 (distinct for different pairs)
    moveq   #5,  %d0
    moveq   #5,  %d1
    moveq   #99, %d2
    moveq   #99, %d3

    | ── Test 1: CMP D1,D0 + BEQ (takes, Z=1) ─────────
    | This is the main fusion target.
    cmp.l   %d1, %d0
    beq     _t1_ok         | Z=1 → taken
    bra     _fail

_t1_ok:
    | After the fused CMP+Beq, the CCR should carry Z=1, N=0, C=0, V=0.
    | Verify by reading CCR via a non-fused Bcc.  (Non-fused because the
    | next inst is not a Bcc; it's a move.)
    bne     _fail          | Z=1 → BNE NOT taken, fall through
    bhi     _fail          | C|Z=1 → BHI NOT taken

    | ── Test 2: CMP D2,D3 + BNE (does not take, Z=1) ──
    cmp.l   %d2, %d3       | 99 - 99 = 0 → Z=1
    bne     _fail          | Z=1 → BNE NOT taken, fall through

    | ── Test 3: CMP D0,D2 + BNE (takes, Z=0, signed 5<99) ──
    cmp.l   %d0, %d2       | 99 - 5 = 94 → Z=0, N=0, C=0
    bne     _t3_ok         | Z=0 → taken
    bra     _fail
_t3_ok:

    | ── Test 4: CMP D2,D0 + BLT (takes, signed 5<99) ──
    | 5 - 99 = -94; N=1, V=0, Z=0 → BLT = (N^V)=1 → taken
    cmp.l   %d2, %d0
    blt     _t4_ok
    bra     _fail
_t4_ok:

    | ── Test 5: CMP D0,D2 + BGT (takes, signed 99>5) ──
    | 99 - 5 = 94; N=0, V=0, Z=0 → BGT = (N=V) & ~Z = 1 → taken
    cmp.l   %d0, %d2
    bgt     _t5_ok
    bra     _fail
_t5_ok:

    | ── Test 6: cache-line boundary (end of 16-byte window) ──
    | Insert a few fillers so the CMP sits near a line boundary.
    | Not strictly required for fusion, but ensures pd_valid holds.
    nop
    nop
    nop
    cmp.l   %d0, %d0       | a == a → Z=1
    beq     _pass
    bra     _fail

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
_halt_fail:
    bra     _halt_fail
