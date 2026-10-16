package m68k040.mmu

import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Shared second-level TLB (`CPU_L2_TLB`, Fable review §4.3(c), week plan F2).
  *
  * A direct-mapped cache of COMPLETED table walks that both table walkers (ITLB and
  * DTLB) probe before their first descriptor read. One synchronous-read RAM
  * (`entries` x 55 bits; 512 entries = one RAMB36 in 512x72 SDP mode) with ONE read
  * port (probe) and ONE write port (fill / sweep).
  *
  * KEY. {vpn (20, already VA[12]-masked by the owner in 8 KB mode), sup (= the root
  * the walk used: SRP vs URP)}. Everything else a walk depends on -- TCR.P, TC.E, the
  * root pointers themselves, the TTRs -- is covered by the FLUSH rule instead.
  *
  * FLUSH. Any `flush` pulse from either client (each client reports its own L1
  * `atcFlush`: PFLUSH/PFLUSHA/PFLUSHN/PFLUSHAN (all of them reach `flushAll`), MOVEC
  * TC (page-size or enable change), SRP/URP, and that side's TTR writes) bumps an
  * 8-bit `epoch`; an entry is live only when its stored epoch equals the current one.
  * On the epoch's wrap to 0 -- and once after reset -- a blind sweep writes every entry
  * invalid; while it runs every probe misses and every fill is dropped, and a flush
  * during the sweep restarts it. So no entry can survive 256 bumps and alias.
  *
  * RACES. A per-client `dirty` bit is set by any flush (same-cycle flush wins) and
  * cleared when that client's probe is accepted: a response (hit) or fill from a
  * client whose probe preceded a flush is suppressed (miss / dropped).
  *
  * PIPELINE. probe.fire (c0, RAM address) -> c1 RAM data -> c2 data register (BRAM
  * output register) -> c3 registered `rsp` (hit computed from registers). One probe in
  * flight per client (each walker probes once and waits), DTLB wins the read port.
  * A fill that loses the write port (both clients in one cycle, or the sweep) is
  * dropped -- dropping a fill is always safe. */
case class L2TlbKey() extends Bundle {
  val vpn  = UInt(20 bits)
  val sup  = Bool()       // root selector (SRP when set)
  val is8K = Bool()       // index selection; part of the context in SURVIVE mode
  val root = UInt(32 bits) // the root pointer the walk uses (SURVIVE-mode context tag)
}

case class L2TlbData() extends Bundle {
  val ppn        = UInt(20 bits)
  val writeProt  = Bool()
  val supervisor = Bool()
  val cacheMode  = CacheMode()
  val modified   = Bool()
}

case class L2TlbRsp() extends Bundle {
  val hit  = Bool()
  val data = L2TlbData()
}

case class L2TlbFill() extends Bundle {
  val key  = L2TlbKey()
  val data = L2TlbData()
}

case class L2TlbEntry() extends Bundle {
  val valid = Bool()
  val epoch = UInt(8 bits)
  val ctx   = UInt(2 bits)
  val sup   = Bool()
  val vpn   = UInt(20 bits)
  val data  = L2TlbData()
}

/** One client's connection (plain fields; the owner plugin allocates the signals and
  * the client drives everything but `probe.ready` and `rsp`). */
case class L2TlbClientPort() extends Bundle {
  val probe = Stream(L2TlbKey())
  val rsp   = Flow(L2TlbRsp())
  val fill  = Flow(L2TlbFill())
  /** EPOCH mode: this side's L1 `atcFlush`. Ignored in SURVIVE mode. */
  val flush = Bool()
  /** SURVIVE mode: physical address of every descriptor read the walker issues. */
  val descRead = Flow(UInt(32 bits))
  /** SURVIVE mode: physical address of this side's own descriptor (U/M) stores. Used
    * only when the owner has no D-cache store port to snoop (standalone DUTs). */
  val storeSnoop = Flow(UInt(32 bits))
}

object L2Tlb {
  /** `hashRoot` (SURVIVE): XOR the root pointer's table-aligned bits into the index, so
    * the same VA under two roots (the ROM's 24/32-bit swap: SRP 0x7fcc00 / 0x7ffa00)
    * lands in different sets of the direct-mapped array instead of evicting each other. */
  def index(key: L2TlbKey, idxBits: Int, hashRoot: Boolean = false): UInt = {
    val v = Mux(key.is8K, key.vpn(idxBits downto 1), key.vpn(idxBits - 1 downto 0))
    // Bit-REVERSED: root tables are 512 B aligned and two roots typically differ in
    // their LOW table-aligned bits (0x7fcc00 vs 0x7ffa00 differ in bits 9..13), while a
    // working set is a CONTIGUOUS run of VPNs (low index bits). Reversal moves the
    // roots' difference into the HIGH index bits, so the two runs land in disjoint sets.
    if (hashRoot) v ^ key.root(9 + idxBits - 1 downto 9).reversed else v
  }
}

/** @param survive      SURVIVE mode (`CPU_L2_TLB_SURVIVE`): entries are tagged by
  *                     context {root pointer, TCR.P} (4-slot context table) and kept
  *                     coherent with memory by snooping; MMU-control writes and
  *                     PFLUSH do NOT clear the array. See the class comment.
  * @param granules     SURVIVE: entries in the descriptor-granule filter (CAM).
  * @param granuleShift SURVIVE: log2 of the filter granule in bytes (7 = 128 B).
  * @param snoops       SURVIVE: number of store-snoop inputs. */
class L2Tlb(entries: Int, clients: Int = 2, survive: Boolean = false,
            granules: Int = 32, granuleShift: Int = 7, snoops: Int = 1) extends Component {
  require(isPow2(entries) && entries >= 64 && entries <= 4096, s"L2 TLB entries must be a power of 2 (got $entries)")
  val idxBits = log2Up(entries)
  val io = new Bundle {
    val probe = Vec.fill(clients)(slave(Stream(L2TlbKey())))
    val rsp   = Vec.fill(clients)(master(Flow(L2TlbRsp())))
    val fill  = Vec.fill(clients)(slave(Flow(L2TlbFill())))
    val flush = in Vec(Bool(), clients)
    /** SURVIVE: descriptor-read addresses (one per client). */
    val descRead = survive generate Vec.fill(clients)(slave(Flow(UInt(32 bits))))
    /** SURVIVE: every store that can change memory as the walkers see it. */
    val snoop = survive generate Vec.fill(snoops)(slave(Flow(UInt(32 bits))))
    /** SURVIVE: a D-cache invalidate that may DISCARD dirty data (CINV of the DC):
      * memory may revert to older contents, so everything is dropped. */
    val inval = survive generate in(Bool())
  }

  val mem = Mem(L2TlbEntry(), entries)

  /** Combinational (from registers) internal flush requests -- SURVIVE mode only. */
  val flushNow = Bool()
  flushNow := False

  // ---- flush: epoch + sweep ----
  // REGISTERED: every flush/fill/sweep decision below runs off flops, so nothing on the
  // owners' (combinational) `atcFlush` cone reaches the RAM's write enable. The one-cycle
  // lag is covered by the owners: a walk in flight across an `atcFlush` is poisoned there
  // (no response, no fill), and a probe accepted on or after the flush cycle is answered
  // against the bumped epoch or masked by `dirty` (set by the same registered pulse).
  val anyFlush = RegNext((if (survive) False else io.flush.orR) || flushNow, init = False)
  val epoch    = RegInit(U(0, 8 bits))
  val sweeping = RegInit(True)               // sweep once after reset (RAM content is undefined)
  val sweepIdx = RegInit(U(0, idxBits bits))
  when(anyFlush) {
    epoch := epoch + 1
    when(epoch === 255 || sweeping) {
      sweeping := True
      sweepIdx := 0
    }
  } elsewhen(sweeping) {
    sweepIdx := sweepIdx + 1
    when(sweepIdx === entries - 1) { sweeping := False }
  }

  // ---- probe (one read port; the HIGHEST client index wins: DTLB over ITLB) ----
  val grant = Vec.fill(clients)(Bool())
  for (c <- 0 until clients) {
    val higher = (c + 1 until clients).map(io.probe(_).valid)
    grant(c) := io.probe(c).valid && (if (higher.isEmpty) True else !higher.reduce(_ || _))
    io.probe(c).ready := grant(c)
  }
  val probeFire = grant.orR
  val probeKey  = MuxOH(grant, io.probe.map(_.payload))
  val rdAddr    = L2Tlb.index(probeKey, idxBits, survive)
  val rdData    = mem.readSync(rdAddr, enable = probeFire)

  val dirty = Vec.fill(clients)(RegInit(True))
  for (c <- 0 until clients) {
    when(grant(c)) { dirty(c) := False }
    when(anyFlush) { dirty(c) := True }
  }

  val s1Valid  = RegNext(probeFire, init = False)
  val s1Grant  = RegNext(grant)
  val s1Key    = RegNext(probeKey)

  // ---- SURVIVE: context table {root, TCR.P} -> 2-bit ctx id ----
  // A probe whose context is not resident allocates a slot (round robin) and flushes the
  // whole array (an entry's ctx id is only meaningful against the slot's CURRENT owner).
  // Mac OS alternates between two roots, so with 4 slots this is a boot-time event.
  val ctxValid = survive generate Vec.fill(4)(RegInit(False))
  val ctxRoot  = survive generate Vec.fill(4)(Reg(UInt(32 bits)))
  val ctxIs8K  = survive generate Vec.fill(4)(Reg(Bool()))
  val ctxPtr   = survive generate RegInit(U(0, 2 bits))
  def ctxMatch(k: L2TlbKey): Vec[Bool] =
    Vec((0 until 4).map(i => ctxValid(i) && ctxRoot(i) === k.root && ctxIs8K(i) === k.is8K))
  val s1Ctx = UInt(2 bits)
  if (survive) {
    val m = ctxMatch(s1Key)
    s1Ctx := OHToUInt(m)
    when(s1Valid && !m.orR) {
      s1Ctx := ctxPtr
      ctxValid(ctxPtr) := True
      ctxRoot(ctxPtr)  := s1Key.root
      ctxIs8K(ctxPtr)  := s1Key.is8K
      ctxPtr := ctxPtr + 1
      flushNow := True          // registered -> anyFlush next cycle: this probe misses
    }
  } else s1Ctx := 0

  val s2Valid  = RegNext(s1Valid, init = False)
  val s2Grant  = RegNext(s1Grant)
  val s2Key    = RegNext(s1Key)
  val s2Ctx    = RegNext(s1Ctx)
  val s2Entry  = RegNext(rdData)            // BRAM output register
  val s2Hit    = s2Entry.valid && s2Entry.epoch === epoch && s2Entry.sup === s2Key.sup &&
                 s2Entry.vpn === s2Key.vpn && s2Entry.ctx === s2Ctx && !sweeping && !anyFlush
  val s3Valid  = RegNext(s2Valid, init = False)
  val s3Grant  = RegNext(s2Grant)
  val s3Hit    = RegNext(s2Hit, init = False)
  val s3Data   = RegNext(s2Entry.data)
  for (c <- 0 until clients) {
    io.rsp(c).valid        := s3Valid && s3Grant(c)
    io.rsp(c).payload.hit  := s3Hit && !dirty(c) && !anyFlush
    io.rsp(c).payload.data := s3Data
  }

  // ---- fill / sweep (one write port) ----
  val fillPick = Vec.fill(clients)(Bool())
  for (c <- 0 until clients) {
    val higher = (c + 1 until clients).map(i => io.fill(i).valid)
    fillPick(c) := io.fill(c).valid && (if (higher.isEmpty) True else !higher.reduce(_ || _))
  }
  val fillV    = RegNext(fillPick, init = Vec.fill(clients)(False))
  val fillReq  = RegNext(MuxOH(fillPick, io.fill.map(_.payload)))
  // SURVIVE: one more register stage resolves the fill's context (it must still be
  // resident; a slot reallocated meanwhile flushed, which `dirty`/`anyFlush` see below).
  val fV    = if (survive) RegNext(fillV, init = Vec.fill(clients)(False)) else fillV
  val fReq  = if (survive) RegNext(fillReq) else fillReq
  val fCtxOk  = Bool()
  val fCtx    = UInt(2 bits)
  if (survive) {
    val m = ctxMatch(fillReq.key)
    fCtxOk := RegNext(m.orR, init = False)
    fCtx   := RegNext(OHToUInt(m))
  } else { fCtxOk := True; fCtx := 0 }
  val fillOk   = (0 until clients).map(c => fV(c) && !dirty(c)).reduce(_ || _) &&
                 fCtxOk && !sweeping && !anyFlush
  val wrEntry  = L2TlbEntry()
  wrEntry.valid := !sweeping
  wrEntry.epoch := epoch
  wrEntry.ctx   := fCtx
  wrEntry.sup   := fReq.key.sup
  wrEntry.vpn   := fReq.key.vpn
  wrEntry.data  := fReq.data
  val wrAddr = Mux(sweeping, sweepIdx, L2Tlb.index(fReq.key, idxBits, survive))
  mem.write(wrAddr, wrEntry, enable = sweeping || fillOk)

  // ---- SURVIVE: descriptor-granule filter + store snoop ----
  // Every descriptor a cached walk read lies in a granule recorded here (inserted when
  // the read is ISSUED, before its data can be returned). A store to a recorded granule
  // (or to the granule being inserted in the same cycle) flushes everything; so does a
  // full filter (it cannot record a new granule) and a D-cache CINV. Any flush empties
  // the filter too: no entry survives that could depend on an untracked granule, and a
  // walk in flight across the flush has `dirty` set, so its fill is dropped.
  //
  // Ordering: a store accepted by the D-cache at cycle t is seen here at t+1 (`snR`); a
  // descriptor read accepted at t' is recorded at t'+1 (`insR`) and visible in `fGran`
  // from t'+2. A read with t' <= t (which may have returned the pre-store value) is
  // therefore matched: by `fGran` if t' < t, by `insR` if t' == t. A read with t' > t
  // is ordered after the store by the D-cache itself and returned the new value.
  val filter = survive generate new Area {
    def gran(a: UInt): UInt = a(31 downto granuleShift)
    val fValid = Vec.fill(granules)(RegInit(False))
    val fGran  = Vec.fill(granules)(Reg(UInt(32 - granuleShift bits)))
    val insV   = Vec(io.descRead.map(d => RegNext(d.valid, init = False)))
    val insG   = Vec(io.descRead.map(d => RegNext(gran(d.payload))))
    val snV    = Vec(io.snoop.map(d => RegNext(d.valid, init = False)))
    val snG    = Vec(io.snoop.map(d => RegNext(gran(d.payload))))
    val invalR = RegNext(io.inval, init = False)
    def inFilter(g: UInt): Bool = (0 until granules).map(i => fValid(i) && fGran(i) === g).reduce(_ || _)
    val snoopHit = (0 until snoops).map { s =>
      snV(s) && (inFilter(snG(s)) || (0 until clients).map(c => insV(c) && insG(c) === snG(s)).reduce(_ || _))
    }.reduce(_ || _)
    // At most one walker descriptor read is accepted per cycle (one D-cache load port);
    // two would be a protocol breach -- answered conservatively with a flush.
    val twoIns  = if (clients > 1) CountOne(insV) > 1 else False
    val insAny  = insV.orR
    val insGSel = MuxOH(OHMasking.last(insV), insG)
    val insNew  = insAny && !inFilter(insGSel)
    val free    = fValid.map(!_)
    val full    = !free.reduce(_ || _)
    val overflow = insNew && full
    when(snoopHit || overflow || twoIns || invalR) { flushNow := True }
    val freeOh = OHMasking.first(Vec(free))
    // `CPU_DC_FREE_CAPTURE` (fable `fanout`): a granule register is read only while its
    // `fValid` is set, so a FREE entry loads `insGSel` every cycle and only entry 0 keeps
    // a clock enable that sees the flush (it is the one rewritten while valid). The
    // filter-CAM insert decision then drives the `fValid` D pins only. Exact.
    val freeCap = m68k040.top.ShippingCoreConfig.dcFreeCapture
    if (freeCap) {
      for (i <- 1 until granules) when(!fValid(i)) { fGran(i) := insGSel }
      when(!fValid(0) || anyFlush) { fGran(0) := insGSel }
    }
    when(anyFlush) {
      fValid.foreach(_ := False)
      when(insAny) { fValid(0) := True; if (!freeCap) fGran(0) := insGSel }
    } elsewhen(insNew && !full) {
      for (i <- 0 until granules) when(freeOh(i)) { fValid(i) := True; if (!freeCap) fGran(i) := insGSel }
    }
    GenerationFlags.simulation {
      val snoopFlushes = Reg(UInt(32 bits)) init 0
      val overflows    = Reg(UInt(32 bits)) init 0
      when(snoopHit) { snoopFlushes := snoopFlushes + 1 }
      when(overflow) { overflows := overflows + 1 }
      snoopFlushes.simPublic(); overflows.simPublic()
    }
  }

  // ---- sim-only statistics ----
  GenerationFlags.simulation {
    val probes = Reg(UInt(32 bits)) init 0
    val hits   = Reg(UInt(32 bits)) init 0
    val fills  = Reg(UInt(32 bits)) init 0
    val flushes = Reg(UInt(32 bits)) init 0
    when(probeFire) { probes := probes + 1 }
    when(io.rsp.map(r => r.valid && r.payload.hit).reduce(_ || _)) { hits := hits + 1 }
    when(fillOk) { fills := fills + 1 }
    when(anyFlush) { flushes := flushes + 1 }
    probes.simPublic(); hits.simPublic(); fills.simPublic(); flushes.simPublic()
  }
  sweeping.simPublic(); epoch.simPublic()
}

/** Provided by `MmuControlPlugin` (the one shared MMU owner). `enabled` is a
  * construction-time constant; when false `port` must not be called. */
trait L2TlbService {
  def l2TlbEnabled: Boolean
  /** Client 0 = ITLB walker, client 1 = DTLB walker (DTLB has read-port priority). */
  def l2TlbPort(client: Int): L2TlbClientPort
  /** SURVIVE mode: an extra physical-address store snoop for writers that bypass the
    * D-cache store port (a DMA master, a debug memory writer). Default idle. */
  def l2TlbExtSnoop: Flow[UInt]
}
object L2TlbService {
  val ITLB = 0
  val DTLB = 1
}
