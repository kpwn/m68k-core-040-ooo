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
  /** D-side write-through / store-drain write. */
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

  // -- table walkers (their own masters today; folded in V2c) ------------------
  /** Table-walker descriptor read. */
  val WALK_READ = 2
  /** Table-walker U/M descriptor write-back. */
  val WALK_WRITE = 3
}
