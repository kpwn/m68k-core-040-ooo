| smc_dcache_to_icache.s — SMC / cache coherency directed test
|
| Background: pre-fix the I-cache fill path read directly from DDR4 with
| no consultation of the D-cache.  If a RAM line had been written via
| the D-cache (held dirty) but never written back to DDR4, an I-cache
| fill for that line saw stale DDR4 — so the CPU executed garbage.
| Q700 HW bug: the wild jump into supervisor stack at PC=0x001fe04c
| decoded bytes 0x003F as illegal because DDR4 held stale data.
|
| This test: stage a tiny program in RAM via D-side store, JSR to it,
| and verify that the bytes EXECUTED are the bytes WRITTEN (not the
| stale RAM that was there before).
|
| PASS sentinel: 0xC0FFEE00
| FAIL sentinels:
|   0xDEAD0001  — the staged routine wasn't executed correctly
|                 (its expected register write didn't happen)
|   0xDEAD0002  — bus error (= wrong-path speculative garbage past the
|                 un-pushed-back staged routine faulted on the bus; the
|                 real symptom this project's core produces for the SAME
|                 underlying coherency gap the original 0xDEAD0004 comment
|                 below describes -- see the "ported-sweep-copyback"
|                 investigation note further down)
|   0xDEAD0004  — vec-4 illegal-instruction trap (= I-cache returned
|                 garbage / stale bytes, the SMC coherency bug)
|
| Investigation note (task smc-dcache-to-icache, 2026-08-19): under the
| default (AsWritten) cache posture this test passes trivially -- CACR is
| never enabled by the test itself, so every access is architecturally
| uncached and every D-side store is observed on the bus immediately,
| keeping the harness's separate I-side memory image in sync with no
| special handling required. Under the harness's "ported-sweep-copyback"
| posture (CACR.DE=1, DTT0/ITT0 COPYBACK, exercising this core's REAL
| cache hardware) this test originally FAILED with a bus-error sentinel.
| Root-caused via cycle-exact whitebox tracing (D-cache S1/S2/S3 pipe,
| DTLB req/rsp, branch-EU S1/completion, fetch-PC stream): the JSR's
| return-address push (0x0000fffc) and the RTS's own pop of that SAME
| address were BOTH independently confirmed correct (pushed and popped
| value identically 0x4080003a) -- this is NOT a D-cache RMW race (the
| stS1SameLineAsS3 / earlyProbeStale classes fixed this session do not
| apply here). Instead, the I-cache's fill of the freshly-staged 8-byte
| routine returned bytes that did NOT match what the D-side stores wrote
| (confirmed via the fetch/predecode packet trace), so predecode computed
| the wrong length for the staged MOVE.L and the trailing RTS opcode was
| never recognized as an instruction at all -- execution silently fell
| through into adjacent (uninitialized) memory, eventually decoding a
| "load" whose garbage effective address bus-faulted for real.
|
| This is EXACTLY the class of gap this test's own header describes --
| except this project's core, on real-68040 grounds, deliberately does
| NOT implement an automatic D-cache-write -> I-cache-fill snoop (see
| commits 69c7b59 / c994092: "No D-store-to-I-cache snoop exists by
| design, matches real 68040 CINV-required semantics"). Genuine MC68040
| silicon has the SAME property: software MUST push the dirty D-cache
| line and invalidate the I-cache line with CPUSHL before executing
| self-modified code, exactly the idiom the rest of this ported corpus
| already uses (ifstage_smc_far_control.s, jmp_full_nomemind_widematrix.s).
| This test, as vendored from upstream m68k-ooo, assumed its origin
| core's non-standard automatic snoop and omitted the mandatory CPUSHL --
| fixed below by adding it, matching the established corpus idiom. No RTL
| change; the D-cache/DTLB/branch-EU behavior traced above is correct.

    .text
    .org 0

_start:
    | Standard setup: stack high, fail handlers.
    lea     0x00010000, %a7
    move.l  #_illegal, 0x00000010       | vec 4 (illegal instr)
    move.l  #_buserr,  0x00000008       | vec 2 (bus error)
    move.l  #_addrerr, 0x0000000c       | vec 3 (address error)

    | Target RAM area for staged code.  Use 0x00020000 — well away
    | from stack + vectors, page-aligned.
    move.l  #0x00020000, %a0

    | Stage a tiny routine at A0:
    |   move.l #0xDEADBEEF, %d7        ; 4 bytes ext-word + 4 bytes imm
    |   rts                            ; 2 bytes
    | Encoding:
    |   move.l #imm32, D7 = 0x2E3C XXXX XXXX (10 bytes total? wait)
    | Actually: move.l #imm, Dn opcode = 0x2X3C where X = Dn*0x200
    |   For D7 dst: bits 11:9 = 111 (= 7), bits 8:6 = 000 (= dest Dn-direct).
    |   16-bit opcode = 0010_1110_0011_1100 = 0x2E3C.  Then 4 bytes imm.
    | Total: 2 + 4 = 6 bytes for the MOVE.L #imm,D7.
    | Plus 2 bytes for RTS = 8 bytes total.
    move.l  #0x2E3C0000, (%a0)          | 1st 4 bytes: opcode + upper imm
    move.l  #0xC0FE4E75, 4(%a0)         | 2nd 4 bytes: lower imm + RTS
                                        | Net: at A0..A0+7 we have
                                        |   2E 3C 00 00 C0 FE 4E 75
                                        | = move.l #0x0000C0FE, D7 ; rts

    | At this point the bytes are in D-cache (probably dirty), but DDR4
    | may still hold whatever was at 0x00020000 before (= zeros most
    | likely, but indeterminate).  Pre-fix: I-cache fill of 0x00020000
    | reads DDR4, gets zeros, decodes 0x0000 as ORI.B #imm,D0 (valid
    | but not what we wrote).  Post-fix: I-cache pre-fill snoop forces
    | D-cache writeback so DDR4 has our staged bytes when I-cache reads.

    | Mandatory 68040 self-modifying-code cache maintenance (see the
    | investigation note above): push the dirty D-cache line covering the
    | staged routine back to memory and invalidate the matching I-cache
    | line, so the upcoming JSR's instruction fetch observes the bytes we
    | just wrote instead of whatever the I-cache/DDR4 held before. Without
    | this, this core (correctly, matching real 68040 CPUSH-required
    | semantics) has no obligation to make the write visible to fetch.
    cpushl  %bc, (%a0)

    | Pre-seed D7 to a sentinel different from the staged value.
    move.l  #0x11111111, %d7

    | Jump to the staged code.  JSR (A0) — A0 = 0x00020000.
    jsr     (%a0)

    | --- if we got here, the staged code executed (RTS returned).
    | Verify D7 was actually written by the staged MOVE.L.
    cmp.l   #0x0000C0FE, %d7
    bne     _fail_d7_wrong

    | Success — write PASS sentinel.
    move.l  #0xC0FFEE00, 0xFFFF0000
    bra     .

_fail_d7_wrong:
    move.l  #0xDEAD0001, 0xFFFF0000
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
