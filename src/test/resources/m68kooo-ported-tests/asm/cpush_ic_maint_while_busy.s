| CPUSHL IC publication regression, ported from the legacy busy-maintenance test.
|
| Enable I-cache explicitly, keep D-cache disabled and MMU off, publish the
| initial generated code with CPUSHA BC, then execute it to prime the I-cache.
| Overwrite it through uncached stores and use the CPUSHL IC under test before
| executing the new code. The first publication is setup synchronization required
| by MC68040 UM section 4.5; it must precede the first JSR.
|
| The following 400 cold NOPs retain fetch pressure from the original test.
| Architectural PASS checks publication and progress. It does not by itself prove
| that the maintenance request overlapped a busy I-cache FSM; that needs a signal
| monitor. Historical RTL module names and FSM states do not describe this core.
|
| Keep this test out of forced-copyback sweeps: its second publication uses IC-only
| maintenance and depends on the data writes reaching memory with D-cache disabled.
|
| PASS: 0xC0FFEE00; failures: 0xDEAD0001 initial code, 0002 bus error,
| 0003 address error, 0004 illegal instruction, 0005 stale code after CPUSHL.

    .text
    .org 0

_start:
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4 (illegal instr)
    move.l  #_buserr,  0x00000008       | vec 2 (bus error)
    move.l  #_addrerr, 0x0000000c       | vec 3 (address error)

    | Make the I-cache premise explicit; leave D-cache disabled and MMU off.
    move.l  #0x00008000, %d0
    movec   %d0, %cacr

    | NEWCODE target — 64-byte-line-aligned RAM address.
    move.l  #0x00020000, %a0

    | Stage OLD code:  move.l #0xBADC0DE1,%d0 ; rts
    move.l  #0x203CBADC, (%a0)
    move.l  #0x0DE14E75, 4(%a0)

    | Publish the initial code before its FIRST execution (MC68040 UM 4.5).
    | Otherwise speculative fetch can see the second long before its write
    | completes, trapping at NEWCODE+6 before the CPUSHL under test is reached.
    | This setup synchronization precedes priming; the later overwrite still
    | relies on the original CPUSHL + cold-NOP sequence below.
    cpusha  %bc

    | Prime: JSR runs the OLD code (also caches this I-cache line).
    jsr     (%a0)
    cmp.l   #0xBADC0DE1, %d0
    bne     _fail_prime

    | Overwrite with NEW code:  move.l #0xCAFEF00D,%d0 ; rts
    | D-cache is disabled, so the writes must reach memory before publication.
    move.l  #0x203CCAFE, (%a0)
    move.l  #0xF00D4E75, 4(%a0)

    | CPUSH IC, LINE, (A0) — must invalidate the stale I-cache line.
    cpushl  %ic, (%a0)

    | Cold NOPs retain fetch pressure around maintenance retirement.
    .rept 400
    nop
    .endr

    | Re-execute NEWCODE.  Stale I-cache -> OLD code re-runs (bug).
    | Genuinely invalidated I-cache -> NEW code runs (fixed).
    move.l  #0x00020000, %a0
    jsr     (%a0)
    cmp.l   #0xCAFEF00D, %d0
    bne     _fail_stale

    | PASS
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_prime:
    move.l  #0xDEAD0001, 0xFFFF0000
    bra     .

_fail_stale:
    move.l  #0xDEAD0005, 0xFFFF0000
    bra     .

_illegal:
    move.l  #0xDEAD0004, 0xFFFF0000
    bra     .

_buserr:
    move.l  #0xDEAD0002, 0xFFFF0000
    bra     .

_addrerr:
    move.l  #0xDEAD0003, 0xFFFF0000
    bra     .
