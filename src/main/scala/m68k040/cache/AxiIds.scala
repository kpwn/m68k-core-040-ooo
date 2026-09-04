package m68k040.cache

/** THE single source of truth for every AXI transaction ID this core emits.
  *
  * Why this exists (MSHR design doc §1.1, §7.1): before this file, IDs were numeric
  * literals scattered across five files, and the DTLB and ITLB walkers BOTH emitted
  * AR id 2 and AW id 3. That is harmless only because they sit on separate physical
  * masters today -- and it becomes a hard bug the instant those masters are folded
  * (which the ratified slice V2c does; NOT built here). It also matters for a
  * different reason: the SoC L2 BLOCKS a second transaction presenting an
  * already-live ID at its front door (`id_busy_c`, `l2c_ctrl.v:140-142,151`), so a
  * constant-ID master gets ZERO benefit from the L2's 8 internal MSHRs. The I side
  * needs two distinct IDs the moment slice I3's prefetch MSHR can be outstanding
  * alongside the demand MSHR.
  *
  * Width: the socket contract fixes AXI_IW = 4
  * (`macqd700-soc/rtl/soc/cpu_socket.vh`) and the crossbar prepends a 2-bit slot tag
  * to reach the L2's ID_WIDTH = 6. So 16 IDs are available per master. The D side and
  * both walkers already used idWidth = 4; the I side had 2 and is widened here.
  *
  * SCOPE NOTE (2026-08-09, IPC-push initiative): this file is the whole of ratified
  * slice V2a that this initiative adopts. V2a.2 (route D-side R/B beats by ID,
  * pool-level `r.ready`) and V2a.3 (tag `DLoadRsp`/`busFaultResp`/`inhibitedResp`/
  * `storeAck`) are enablers for slice D2 -- which the design doc itself marks
  * "E2E? NO -- contingent on §11.Q3" (the SoC crossbar is single-outstanding per
  * master port, so D2 delivers nothing end-to-end until that is reworked). They are
  * deliberately NOT built here: they carry real regression surface on the D-side load
  * path for zero measurable IPC on today's fabric. V2b (I-cache 256->128-bit) and V2c
  * (walker fold) are socket-conformance work, explicitly out of this initiative's
  * scope.
  *
  * RULE: never write a numeric AXI ID literal anywhere else in `src/main`. */
object AxiIds {
  /** AXI ID width for BOTH physical masters. Socket-conformant. */
  val ID_W = 4

  // -- D master (`DcachePlugin_logic_axi`) ------------------------------------
  /** Read ID of D-cache load-refill MSHR `k`. Reserved range 0..3 (room for
    * N_MSHR = 4); only 0 is used while N_MSHR == 1. */
  def dRefill(k: Int): Int = { require(k >= 0 && k < 4, s"D refill MSHR index $k out of 0..3"); k }
  /** D-side write-through / store-drain write. A SINGLE constant id, reused for
    * every WRITETHROUGH/INHIBITED store beat (unlike the read side's per-MSHR
    * `dRefill`/`iRefill` pools) -- this is safe for pipelined (overlapping)
    * back-to-back writes ONLY because AXI4 guarantees in-order completion for
    * transactions sharing one id, and because this id's traffic is cache-path
    * (cacheable RAM), never the bypass window: the SoC L2's `id_busy_c` front-door
    * CAM (the mechanism this file's own header comment warns "gets ZERO benefit
    * from the L2's 8 internal MSHRs" for a constant-ID READ master) was rescoped
    * in the L2's 2026-08-19/20 pipeline rework (`l2c_ctrl.v`, "SINCE THE PIPELINE
    * this applies at the door only to a beat bound for the BYPASS ENGINE; a
    * cache-path beat is ordered at the resolve stage instead"
    * (`ord_now_block_c`/`ord_merge_block_c`) -- so a constant-ID WRITE on the
    * cache path is NOT id-busy-blocked the way a constant-ID READ still would be.
    * Do not read the header comment above as still describing the write side.
    *
    * CAVEAT (task: pipeline WRITETHROUGH stores) -- the L2 is not the binding
    * constraint for same-master write overlap; `macqd700-soc/rtl/soc/axi_xbar.v`
    * is: its `sw_owned` mechanism locks a slave's whole AW->B sequence to ONE
    * master (1 outstanding write per master port), and a 2026-07-21 investigation
    * recorded in that file explicitly analyzed "same-master pipelining (the case
    * that would actually help the CPU store stream)" and deliberately did NOT
    * implement it (a real same-ID B-ordering risk if done carelessly, "a
    * substantial, risk-bearing redesign", SoC-side, out of scope here). This
    * CPU-side id reuse is still correct and worth doing -- the crossbar's
    * `mw_awready` simply won't assert for a second AW until the first's B is
    * consumed, so a second kickoff issued early just backpressures cleanly on
    * ordinary `axi.aw.ready` -- but the REAL, currently-deployed throughput gain
    * from that reuse is bounded to "hide this core's own S0-S3 admission latency
    * behind an in-flight write's round trip", not "fully overlap two round
    * trips", until/unless the crossbar itself is reworked. */
  val D_STORE = 1
  /** D-side cache-maintenance (CPUSH) writeback write. */
  val D_PUSH = 2
  /** D-side eviction writeback write. */
  val D_EVICT = 4

  // -- I master (`IcachePlugin_logic_axi`) ------------------------------------
  /** I-cache refill slot `k`: slot 0 is reserved for demand, slots 1..4 are the
    * five-ID stream-prefetch window. Responses are routed solely by this ID. */
  def iRefill(k: Int): Int = {
    require(k >= 0 && k < 5, s"I refill slot $k out of 0..4")
    k
  }
  /** I-cache DEMAND refill slot. */
  val I_DEMAND = iRefill(0)
  /** Inclusive silent-refill ID range and its compile-time slot count. */
  val I_SPEC_BASE  = iRefill(1)
  val I_SPEC_LAST  = iRefill(4)
  val I_SPEC_SLOTS = I_SPEC_LAST - I_SPEC_BASE + 1
  /** Compatibility name for the first silent speculative slot. New code that
    * selects a particular slot must use `iRefill(k)` instead. */
  val I_PREFETCH = I_SPEC_BASE

  // -- table walkers: RESERVED-BUT-UNUSED since 2026-09-04 ---------------------
  // The ITLB and DTLB table walkers no longer emit AXI at all: their descriptor reads
  // and their U/M descriptor writebacks are ordinary `DcacheService` client traffic,
  // arbitrated inside `LsEuPlugin` and issued (when they miss) as the D-cache's own
  // refill / write-through under the D-cache's OWN ids. No `src/main` file references
  // either constant any more; `socket/AxiDMergeSpec` still uses them to drive its own
  // stimulus for the arbiter's now-idle walker ports.
  //
  // THE VALUES ARE DELIBERATELY KEPT AND MUST NOT BE RENUMBERED. Renumbering to "fix"
  // the collision this header warns about below is explicitly forbidden by the socket
  // plan's own Global Constraints, and the collision is in any case now DISSOLVED
  // rather than mitigated: there is no walker master left to collide with anything.
  /** Table-walker descriptor read. RESERVED; no longer emitted. */
  val WALK_READ = 2
  /** Table-walker U/M descriptor write-back. RESERVED; no longer emitted. */
  val WALK_WRITE = 3

  // -- reset-vector reader (axi-socket adapter spec D12/D13, section 6.2) --------
  /** The boot vector-0 read, issued as a fourth READ owner on the `axi_d` merge
    * arbiter -- never as a third socket master, because `axi_xbar.v:1178-1192`'s
    * `apply_cpu_overlay` aliases low addresses into the ROM mirror only for reads whose
    * master index is `XBAR_M_CPU`/`XBAR_M_CPUI`.
    *
    * Its VALUE is architecturally irrelevant under D8 (routing is by the arbiter's
    * latched owner tag, never by ID), but it must not alias a live D-side ARID, so it
    * sits outside the D-refill reserved range 0-3 and outside the walkers' AR=2. */
  val RESET_VEC = 5
}
