| movea_sp_sp_plain_load.s — MOVEA.L (SP),SP must be a PLAIN load.
|
| HW ILA capture (2026-07-14, "MOVEA.L (SP),SP wild jump" investigation):
| during real Q700 ROM boot the CPU executed MOVEA.L (A7),A7 (0x2E57)
| and behaved like RTS — the loaded value V ended up as the next fetch
| PC (rob_brtgt == dc_rdata == V) and A7 took an extra +4 on top of the
| plain load.  MOVEA.L <ea>,An must NEVER redirect control flow and the
| plain (An) indirect mode has NO postincrement.
|
| This test runs the exact idiom in a tight loop (many iterations) so an
| external IRQ sweep (+ipl=<cyc>:1 plusargs) can strafe every pipeline
| phase of the instruction: dispatch, issue, LSU in-flight, completion,
| retire, and the exception-inject windows around it.
|
| Wild-jump detection: the loaded value V points at a landing zone
| filled with ILLEGAL (0x4AFC) opcodes; any control-flow transfer to V
| traps to vec 4 and writes a FAIL sentinel.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.
| FAIL sentinels:
|   0xDEAD0007 — A7 != V after MOVEA.L (SP),SP (e.g. extra +4 applied)
|   0xDEAD0004 — vec-4 illegal (wild jump into the landing zone)
|   0xDEAD0002 — vec-2 bus error (wild jump into unmapped space)
|   0xDEAD0003 — vec-3 address error

    .text
    .org 0

_start:
    | Supervisor stack + unmask level-1 IRQs.
    lea     0x00020000, %a7
    move.w  #0x2000, %sr

    | Fail-trap handlers.
    move.l  #_buserr,  0x00000008    | vec 2
    move.l  #_addrerr, 0x0000000c    | vec 3
    move.l  #_illegal, 0x00000010    | vec 4
    move.l  #_irq_l1,  0x00000064    | vec 25 (autovector level 1)

    | Fill the wild-jump landing zone (V = 0x00018000) with ILLEGAL.
    lea     0x00018000, %a0
    move.w  #31, %d1
_fill:
    move.w  #0x4AFC, (%a0)+
    dbf     %d1, _fill

    | Loop the idiom so an IRQ sweep hits every pipeline phase.
    move.w  #199, %d5
_loop:
    lea     0x00010000, %a7          | A7 = X
    move.l  #0x00018000, (%a7)       | mem[X] = V (landing zone)

    | --- THE INSTRUCTION UNDER TEST ------------------------------
    movea.l (%a7), %a7               | A7 := mem[A7] = V.  NO redirect,
                                     | NO postincrement.
    | --- if we get here, no wild jump happened -------------------

    cmpa.l  #0x00018000, %a7
    bne     _fail_a7

    lea     0x00020000, %a7          | restore a sane stack
    dbf     %d5, _loop

    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_a7:
    lea     0x00020000, %a7
    move.l  #0xDEAD0007, 0xFFFF0000
    bra     .

_buserr:
    lea     0x00020000, %a7
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .

_addrerr:
    lea     0x00020000, %a7
    move.l  #0xDEAD0003, 0xFFFF0000
    bra     .

_illegal:
    lea     0x00020000, %a7
    move.l  #0xDEAD0004, 0xFFFF0000
    bra     .

_irq_l1:
    addq.l  #1, _witness
    rte

_witness:
    .long   0
