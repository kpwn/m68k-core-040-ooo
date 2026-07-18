| stack_return_low_cache_rewrite.s -- low-stack RTS target rewritten in D-cache
|
| ROM frontier covered:
|   The Q700 ROM is currently reaching an overlay-off RTS near ROM PC
|   0x4080401c which pops a return PC from an A7 value around
|   0x0000fece.  This test keeps that stack/return class directed and
|   small in the existing asm harness:
|
|     * BSR pushes a return address in the low stack window, the callee
|       verifies it, rewrites it, and RTS must use the loaded stack value.
|       The aligned BSR stack slot is read back while hot after the dirty
|       rewrite, before any cache push or eviction.
|     * JSR/RTS then checks ordinary subroutine stack discipline on the
|       same low stack line.
|     * The exact low-stack frontier is exercised by setting
|       A7 = 0x0000fece, storing a rewritten return PC through D-cache,
|       and executing RTS.
|       The exact unaligned slot is checked while hot in D-cache.  The
|       return target itself is a normal high-ROM asm label because this
|       harness only auto-loads directed binaries at 0x40800000.
|
| PASS sentinel: 0xC0FFEE00 -> 0xFFFF0000.

    .text
    .org 0

_start:
    movea.l #0x0000fed0, %a7

    | BSR must push _after_bsr.  The callee rewrites the stacked return
    | address to _after_rewrite; reaching _after_bsr is a failure.
    bsr     _bsr_rewrite
_after_bsr:
    bra     _fail

_after_rewrite:
    cmpa.l  #0x0000fed0, %a7
    bne     _fail
    cmp.l   #0x13572468, %d0
    bne     _fail

    | BSR pushed at 0x0000fecc.  The callee rewrote that aligned slot;
    | read it while hot from the dirty D-cache line.
    movea.l #0x0000fecc, %a4
    move.l  (%a4), %d4
    cmp.l   #_after_rewrite, %d4
    bne     _fail

    | JSR must push the fall-through PC and RTS must restore A7.
    jsr     _jsr_probe
_after_jsr:
    cmpa.l  #0x0000fed0, %a7
    bne     _fail
    cmp.l   #0x24681357, %d1
    bne     _fail

    | Memory-state canary: final PASS requires a normal cached store/load
    | in RAM to survive before the exact low-stack RTS sequence.
    lea     0x00010020, %a0
    move.l  #0x55aa33cc, (%a0)
    move.l  (%a0), %d2
    cmp.l   #0x55aa33cc, %d2
    bne     _fail

    | Exact low-stack frontier shape: RTS pops a long from A7=0x0000fece.
    | The store is intentionally left dirty in D-cache; no CPUSH is done
    | for this stack line.
    movea.l #0x0000fece, %a7
    move.l  #_after_exact, (%a7)
    rts

    | Fall-through from RTS would mean no redirect happened.
    bra     _fail

_after_exact:
    cmpa.l  #0x0000fed2, %a7
    bne     _fail

    | While the stack line is hot, the rewritten return PC must be visible.
    movea.l #0x0000fece, %a4
    move.l  (%a4), %d4
    cmp.l   #_after_exact, %d4
    bne     _fail

    lea     0xFFFF0000, %a1
    move.l  #0xC0FFEE00, %d7
    move.l  %d7, (%a1)
_halt:
    bra     _halt

_bsr_rewrite:
    move.l  (%a7), %d0
    cmp.l   #_after_bsr, %d0
    bne     _fail
    move.l  #0x13572468, %d0
    move.l  #_after_rewrite, (%a7)
    rts

_jsr_probe:
    move.l  (%a7), %d1
    cmp.l   #_after_jsr, %d1
    bne     _fail
    move.l  #0x24681357, %d1
    rts

_fail:
    lea     0xFFFF0000, %a1
    move.l  #0xDEADBEEF, %d7
    move.l  %d7, (%a1)
_halt_fail:
    bra     _halt_fail
