| lsu_predec_long_burst.s — many predec long loads in a tight loop
|
| Q700 boot frontier: 33-load periodicity in `move.l -(A0), D3` returning
| WRONG data (0x7FFFFC12) every 33 iterations.  Hypothesis: LSU/ROB
| corner case at high in-flight count (ROB has 32 entries).
|
| Test: seed memory with a known monotonic pattern; do N=64 predec long
| loads; verify each loaded value matches the expected pattern.

    .text
    .org 0

_start:
    | Seed PA 0x10000..0x10100 with monotonic longs:
    |   0x10000 = 0x10000_PATTERN, 0x10004 = 0x10004_PATTERN, ...
    | Pattern = 0xAA000000 | (offset >> 2) so each long is unique.
    lea     0x00010000, %a0
    move.l  #64, %d0          | count
    move.l  #0xAA000000, %d1  | base pattern

_seed_loop:
    move.l  %d1, (%a0)+
    addq.l  #1, %d1
    subq.l  #1, %d0
    bne     _seed_loop

    | Now A0 = 0x10100 (one past last seeded long).
    | Read back via predec loop.  Expected per iter K (0..63):
    |   A0_pre  = 0x10100 - K*4
    |   A0_post = 0x10100 - (K+1)*4
    |   D3      = pattern at A0_post = 0xAA000000 + (0x10100 - (K+1)*4 - 0x10000)/4
    |          = 0xAA000000 + (0x100 - (K+1)*4)/4
    |          = 0xAA000000 + 64 - (K+1)
    | so for K=0..63: D3 = 0xAA00003F, 0xAA00003E, ..., 0xAA000000.

    move.l  #64, %d2          | count

    | Loop with explicit counter to read all 64 longs.
    move.l  #0xAA00003F, %d4  | expected current value
_read_loop:
    move.l  -(%a0), %d3
    cmp.l   %d4, %d3
    bne     _fail
    subq.l  #1, %d4
    subq.l  #1, %d2
    bne     _read_loop

    | Stage 2: same but with bfextu after each load (mimics ROM shape).
    | Re-seed.
    lea     0x00010000, %a0
    move.l  #64, %d0
    move.l  #0xAA000000, %d1
_seed_loop2:
    move.l  %d1, (%a0)+
    addq.l  #1, %d1
    subq.l  #1, %d0
    bne     _seed_loop2

    move.l  #64, %d2
    move.l  #0xAA00003F, %d4
    moveq   #0, %d5
_read_loop2:
    move.l  -(%a0), %d3
    bfextu  %d3 {0:32}, %d6   | extract all 32 bits — should equal D3
    cmp.l   %d4, %d6
    bne     _fail
    subq.l  #1, %d4
    subq.l  #1, %d2
    bne     _read_loop2

_pass:
    lea     0xFFFF0000, %a0
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a0)
_halt:
    bra     _halt

_fail:
    lea     0xFFFF0000, %a0
    move.l  %d3, %d7         | return loaded value for diagnosis
    move.l  %d7, (%a0)
_halt_fail:
    bra     _halt_fail
