| mmu_flush_queue_movec_pflusha_race.s
|
| Flush-queue back-to-back MOVEC-TC + PFLUSHA stress (2026-07-16/17
| real-HW boot-deadlock investigation).
|
| THEORY that motivated this test (rtl/core/m68k_core_flush.vh): the
| 3-source flush-request queue (EXC / MMU / DBG) tracks "already
| enqueued for this source" with a plain falling-edge-of-level formula
| (mmu_req_enqueued && mmu_dcache_flush_req).  `mmu_dcache_flush_req` is
| a LEVEL that a fresh MOVEC-TC/PFLUSHA/PFLUSH-VA retire re-asserts
| instead of letting fall (by design, so an overlapping EXC/DBG flush
| doesn't spuriously clear it).  On paper, if that fresh retire lands on
| the exact cycle the PREVIOUS MMU-tagged FIFO entry dequeues
| (mmu_flush_done_pulse), the level never visibly drops to 0 across the
| transition, the tracker wrongly concludes "still enqueued", and the
| new event's flush is never (re-)enqueued — `mmu_flush_done_pulse`
| never fires again, `mmu_dcache_flush_req` never clears, and
| `lsu_ready_to_iqmem` / the I-side fetch gate wedge shut permanently.
| A defensive fix for exactly this coincidence was applied to
| rtl/core/m68k_core_flush.vh (force the enqueued-tracking bit back to
| 0 on the coincidence cycle instead of trusting the level).  It is
| provably behaviour-preserving off the coincidence cycle (see the
| in-RTL comment) and this test's sim result is bit-for-bit identical
| with the fix applied vs. reverted — cycles=166107, committed=33427
| either way — which is exactly what "behaviour-preserving, race not
| reached" looks like.
|
| IMPORTANT — this test does NOT reproduce a hang, fixed or unfixed.
| Reverting the m68k_core_flush.vh fix and re-running this exact test
| still PASSES.  Root-cause investigation (this session) traced why:
| if_mmu_xlate.v re-queries the MMU (S_XLATE) on every new fetch
| request, gated by `mmu_resp_ready_in = immu_resp_ready &
| ~mmu_dcache_flush_req & ~flush_wake` (m68k_core_fetch.vh) — so
| instruction FETCH itself stalls solid for the full duration of any
| pending flush.  That self-throttles how far commit can race ahead of
| a live flush: the CORE_DEBUG [FQ] trace of this exact test shows
| every MOVEC-TC/PFLUSHA pair fully draining (dequeue, `done=1`) before
| the next pair's enqueue, with no overlap, even at delay=0 (the two
| trigger events placed back-to-back with zero NOPs between them).  The
| one-cycle "second event retires exactly when the first dequeues"
| coincidence this test set out to hit therefore never materializes via
| ordinary sequential fetch-driven code — reaching it (if it is in fact
| reachable on real HW) likely needs either a MUCH longer flush (real
| dirty dcache lines needing writeback, not this test's all-clean TC.E=0
| case) to widen the window fetch can race ahead in once unblocked, or
| a trigger that bypasses the front-end fetch gate entirely (e.g. a
| DBG/JTAG-injected flush racing a commit-driven one).  Left as an open
| lead for whoever picks this back up — see the m68k_core_flush.vh
| comment for the exact mechanism and docs/ for session notes.
|
| This test is kept as directed flush-queue arbiter coverage (a real,
| previously-untested back-to-back MOVEC-TC+PFLUSHA sequencing pattern,
| swept across a 0..255-NOP static delay range for good measure) and as
| a behaviour-preservation regression for the m68k_core_flush.vh
| defensive fix, NOT as a confirmed repro of the live-HW wedge.
|
| The race window (if real) would be exactly ONE cycle wide, so a
| *dynamic* (dbra-loop) delay sweep aliases past it — dbra itself costs
| an extra retire cycle per iteration, so a loop-driven sweep only
| samples every-other-cycle offsets and could skip a single vulnerable
| cycle entirely (an earlier dbra-loop version of this test was
| replaced for exactly this reason).  This version uses GAS `.rept` to
| unroll a STATIC, monotonically increasing NOP count before each
| PFLUSHA instead — every delay value in the swept range gets its own
| straight-line code, so consecutive tries differ by exactly one extra
| NOP retire-cycle with no loop overhead in between, giving true
| single-cycle-granularity coverage of whatever window does exist.
|
| TC.E is left 0 throughout (any write to CR#4 trips the flush-queue
| path regardless of value) so no real page tables are needed and every
| load/store stays plain VA==PA passthrough — this isolates the
| flush-queue arbiter from the (separately, already well-tested)
| walker/ATC machinery.
|
| PASS sentinel: 0xC0FFEE00 — swept the whole delay range AND the
| post-sweep liveness load/store still completes (i.e. the memory
| pipeline never wedged).  If some variant of the race IS reachable,
| the expected failure mode is a hang, caught by the tb harness's
| +timeout as a FAIL, at whichever delay hits it.
|
| FAIL sentinel:
|   0xDEAD0701 — liveness check after the sweep read back the wrong
|                value (memory pipeline still limping, not a full
|                wedge, but not correct either).

    .text
    .org 0

    .equ PASS_SENT, 0xFFFF0000

    .macro MMU_FLUSH_RACE_TRY delay
    move.l  #0, %d0
    movec   %d0, %tc
    .rept \delay
    nop
    .endr
    pflusha
    .endm

_start:
    lea     0x00010000, %a7
    move.l  #_trap_h, 0x00100080
    move.l  #0x00100000, %d0
    movec   %d0, %vbr

    | Static, monotonically-increasing delay sweep (0..255 NOPs) between
    | the MOVEC-TC write and the following PFLUSHA — see header comment
    | for why this must be unrolled rather than loop-driven.
    .irp d,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,130,131,132,133,134,135,136,137,138,139,140,141,142,143,144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,160,161,162,163,164,165,166,167,168,169,170,171,172,173,174,175,176,177,178,179,180,181,182,183,184,185,186,187,188,189,190,191,192,193,194,195,196,197,198,199,200,201,202,203,204,205,206,207,208,209,210,211,212,213,214,215,216,217,218,219,220,221,222,223,224,225,226,227,228,229,230,231,232,233,234,235,236,237,238,239,240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255
    MMU_FLUSH_RACE_TRY \d
    .endr

    | Liveness check: ordinary memory pipeline must still work after
    | the whole sweep — if the flush-queue wedged on any iteration,
    | execution never reaches here at all (caught by the harness
    | timeout instead of this explicit check).
    move.l  #0x12345678, 0x00020000
    move.l  0x00020000, %d3
    cmp.l   #0x12345678, %d3
    bne     _fail_live

    trap    #0

_fail_live:
    move.l  #0xDEAD0701, 0xFFFF0000
_haltf:
    bra     _haltf

_trap_h:
    move.l  #0xC0FFEE00, 0xFFFF0000
_done:
    bra     _done
