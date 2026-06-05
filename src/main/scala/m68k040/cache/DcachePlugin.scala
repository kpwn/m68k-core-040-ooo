package m68k040.cache

import m68k040.isa.Size
import m68k040.services.DTranslationService
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesizable VIPT write-through L1 data cache.
  *
  * Geometry: 128 sets, 4 ways, 16-byte lines (8 KiB total). One 128-bit AXI beat
  * == one whole line (len=0). Write-through / no-allocate / never-dirty:
  *   - LOAD: VIPT 2-cycle read (S0 launch BRAM read + translate; S1 tag-compare,
  *     way-mux, byte-lane extract). Miss -> single-beat refill -> replay.
  *   - STORE: physical (SQ already translated). RMW the line if it hits; ALWAYS
  *     issue an AXI write (write-through). No allocate on a miss.
  *
  * Data array: one synchronous-read BRAM per way, single (muxed) write port
  * (refill has priority over store-update — they never collide in this slice's
  * single-outstanding LS pipe). Tags: async LUTRAM. Valids: register array. */
class DcachePlugin extends FiberPlugin with DcacheService {

  private val geo     = CacheGeometry(cacheBytes = 8192, lineBytes = 16, ways = 4,
                                      indexingPolicy = CacheIndexingPolicy.Vipt)
  geo.requireViptSafe(4096, "L1D")
  private val ways    = geo.ways
  private val sets    = geo.sets
  private val tagBits = geo.tagBits          // 21
  private val setBits = geo.indexBits        // 7
  private val offBits = geo.offsetBits       // 4
  private val wayBits = log2Up(ways)

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {

    // ---- service ports (plain directionless Stream/Flow) ----
    val loadCmdPort = Stream(DLoadCmd())
    val loadRspPort = Flow(DLoadRsp())
    val loadBusyReg = Bool()
    val storePort   = Flow(DStoreCmd())
    val storeAckReg = Bool()
    val axi         = master(Axi4(axiCfg))

    // ---- translation (D-side TLB) ----
    // The LS EU is the translate-at-execute requester: it DRIVES `xlate.req` (the
    // access VPN, write?, supervisor?) — for both loads (presented on loadCmd) and
    // stores (SQ-alloc paddr). The cache only READS `xlate.rsp`: it forms the load
    // hit-tag from `rsp.ppn` (consistent because the LS EU drives req.vpn from the
    // same vaddr it puts on loadCmd) and gates load acceptance on `rsp.ready` (a
    // DTLB miss holds it low while the walker runs -> the load is not accepted and
    // the LS EU stalls on its existing single-outstanding back-pressure path).
    val xlate = host[DTranslationService]

    // ---- storage ----
    val dataMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- single muxed data write port per way ----
    val wrEn   = Vec.fill(ways)(False)
    val wrSet  = Vec.fill(ways)(U(0, setBits bits))
    val wrData = Vec.fill(ways)(B(0, 128 bits))
    for (w <- 0 until ways) dataMem(w).write(wrSet(w), wrData(w), wrEn(w))

    // ---- shared synchronous data read port per way (S0 launch -> S1 result) ----
    val rdSet = UInt(setBits bits)
    val rdEn  = Bool()
    rdSet := U(0, setBits bits)
    rdEn  := False
    val rdData = Vec(dataMem.map(_.readSync(rdSet, rdEn)))

    // ---- miss-state latches ----
    val missPaddr = Reg(UInt(32 bits))   // physical addr of the missing line (AXI refill base)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val missOff   = Reg(UInt(offBits bits))
    val missSize  = Reg(Size())
    val victimWay = Reg(UInt(wayBits bits))
    val arSent    = Reg(Bool()) init False

    // ---- S1 (load response build) pipeline registers ----
    val s1Valid = Reg(Bool()) init False
    val s1Way   = Reg(UInt(wayBits bits))
    val s1Off   = Reg(UInt(offBits bits))
    val s1Size  = Reg(Size())
    val s1Fault = Reg(Bool())
    s1Valid := False  // default; armed on a hit-accept below

    // ---- defaults ----
    loadCmdPort.ready := False
    axi.ar.valid := False
    axi.ar.payload.assignDontCare()
    axi.r.ready  := False
    axi.aw.valid := False
    axi.aw.payload.assignDontCare()
    axi.w.valid  := False
    axi.w.payload.assignDontCare()
    axi.b.ready  := True

    val busy = Reg(Bool()) init False
    loadBusyReg := busy

    // ---- load hit detection (combinational from cmd vaddr + PRE-TRANSLATED paddr) ----
    // FMax: the physical tag comes from the requester's REGISTERED `loadCmd.paddr`
    // (translate-at-execute already resolved it into a register), NOT from the live
    // DTLB lookup `xlate.rsp.ppn`. This removes the DTLB way-mux/ppn select from the
    // cache tag-compare -> hit -> dataMem-read arc (the post-MMU critical path).
    // VIPT: index on the page-invariant vaddr set bits; tag on the physical bits.
    val cmdVaddr = loadCmdPort.payload.vaddr
    val cmdPaddr = loadCmdPort.payload.paddr
    val cmdSet   = cmdVaddr(offBits + setBits - 1 downto offBits)
    val cmdOff   = cmdVaddr(offBits - 1 downto 0)
    // physical tag = paddr[31:offBits+setBits].
    val cmdTag = cmdPaddr(31 downto offBits + setBits)
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := valids(w)(cmdSet) && (tagMem(w).readAsync(cmdSet) === cmdTag)
    val isHit     = hitVec.orR
    val hitWayIdx = OHToUInt(hitVec)

    // ---- S1 -> load response (combinational from the registered stage) ----
    val s1Line = rdData(s1Way)
    val s1Ext  = DcacheByteLane.extract(s1Line, s1Off, s1Size)
    loadRspPort.valid       := s1Valid
    loadRspPort.payload.data  := s1Ext
    loadRspPort.payload.line  := s1Line
    loadRspPort.payload.fault := s1Fault

    // ---- STORE write-through (PIPELINED RMW: store-S0 read / store-S1 merge+write) ----
    // FMax: the binding full-core path was exc-FSM -> (DTLB cone) -> dataMem store
    // write-data, because the RMW (readAsync old line + 16-lane byte-merge + write)
    // was a single combinational cycle fronted by the far-placed exc-FSM/DTLB cone
    // (route 71.7%). Split it:
    //   store-S0 (on storePort.valid): latch {mergeData, mergeStrb, set, per-way hit,
    //     per-way old-line readAsync, axi line addr} into regs PHYSICALLY at the cache.
    //   store-S1 (next cycle): byte-merge the registered old line with the registered
    //     store bytes and drive the dataMem write port; the merge+write now starts
    //     from flops at the dataMem (a local cone), and the long exc-FSM/DTLB route
    //     ends at the S0 latch. Latency-agnostic lock-step makes the +1 cycle free.
    // Single-outstanding: the producer never presents a 2nd store before this one's
    // AXI B (storeAck) lands, so S0/S1 never overlap a second store.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True

    // ---- store-S0: PURE payload latch (the cache-boundary flop) ----
    // FMax: the store paddr arrives on `storePort` off a LONG cone (the LS-EU FSM
    // -> DTLB walker/hit -> store-port mux, route-dominated). Doing hit-detect +
    // old-line readAsync + 16-lane merge + dataMem.write off that LIVE paddr was the
    // binding path. So S0 does NOTHING but flop the raw store payload (a short
    // route ending at a register physically at the cache). EVERYTHING else (set
    // decode, hit-detect via tagMem.readAsync, old-line readAsync, merge, write)
    // happens in store-S1 from the REGISTERED payload, fully decoupled from the
    // DTLB cone. Single-outstanding => valids/tagMem cannot change between S0 and
    // S1 (no concurrent refill), so S1 hit-detect is correct.
    val s0Valid   = RegInit(False)
    val s0Payload = Reg(DStoreCmd())
    s0Valid := False  // default; armed on a store-accept below
    when(storePort.valid) {
      s0Valid   := True
      s0Payload := storePort.payload
    }

    // ---- store-S1: decode set/off/tag + hit-detect + old-line read + merge + write ----
    // Placed BEFORE the LOAD FSM so a same-cycle refill write (in REFILL) overrides
    // this store write (refill has priority). Single-outstanding guarantees they
    // never actually collide; this preserves the original last-assignment ordering.
    val stS1Set = s0Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS1Off = s0Payload.paddr(offBits - 1 downto 0)
    val stS1Tag = s0Payload.paddr(31 downto offBits + setBits)
    val stS1Hit = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stS1Hit(w) := valids(w)(stS1Set) && (tagMem(w).readAsync(stS1Set) === stS1Tag)
    // Aligned/probe path derives the merge from {data,size,offset}; a SPLIT store
    // slot supplies an explicit line-relative strobe + 128-bit line-aligned data.
    val mergeData = Mux(s0Payload.useStrb,
      s0Payload.lineData,
      DcacheByteLane.storeData(stS1Off, s0Payload.size, s0Payload.data))
    val mergeStrb = Mux(s0Payload.useStrb,
      s0Payload.strb,
      DcacheByteLane.storeStrb(stS1Off, s0Payload.size))
    val stS1MrgBytes = mergeData.subdivideIn(8 bits)
    when(s0Valid) {
      for (w <- 0 until ways) when(stS1Hit(w)) {
        val curBytes = dataMem(w).readAsync(stS1Set).subdivideIn(8 bits)
        val newBytes = Vec(Bits(8 bits), 16)
        for (i <- 0 until 16) newBytes(i) := Mux(mergeStrb(i), stS1MrgBytes(i), curBytes(i))
        wrEn(w)   := True
        wrSet(w)  := stS1Set
        wrData(w) := newBytes.asBits
      }
      // latch the write-through beat payload (AXI aw/w fire after S1, unchanged
      // relative ordering: still one cycle after the store is presented + merged).
      stMergeReg := mergeData
      stStrbReg  := mergeStrb
      stAddrReg  := (s0Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt  // line-aligned
      stAwDone   := False
      stWDone    := False
    }

    // AXI write-through driver (single 128-bit beat).
    when(!stAwDone) {
      axi.aw.valid         := True
      axi.aw.payload.addr  := stAddrReg
      axi.aw.payload.id    := U(1, 4 bits)
      axi.aw.payload.len   := U(0, 8 bits)
      axi.aw.payload.size  := U(4, 3 bits)
      axi.aw.payload.burst := Axi4.burst.INCR
      when(axi.aw.ready) { stAwDone := True }
    }
    when(!stWDone) {
      axi.w.valid        := True
      axi.w.payload.data := stMergeReg
      axi.w.payload.strb := stStrbReg
      axi.w.payload.last := True
      when(axi.w.ready) { stWDone := True }
    }

    // ---- store write-through ACK ----
    // The write has landed in memory once the AXI B response handshakes (b.ready is
    // always True). The SQ holds the drained entry resident — still forwarding — until
    // this pulse, closing the stale-refill window for a younger load that misses L1D.
    storeAckReg := axi.b.valid && axi.b.ready

    // ---- LOAD FSM ----
    val fsm = new StateMachine {
      val IDLE   = new State with EntryPoint
      val REFILL = new State
      val REPLAY = new State

      val inFlight = s1Valid || loadRspPort.valid

      IDLE.whenIsActive {
        busy := False
        // Accept a load only when translation is RESOLVED this cycle (TLB hit or
        // identity). On a DTLB miss `rsp.ready` is False while the walker runs, so
        // the load is not accepted and the LS EU stays on its existing single-
        // outstanding back-pressure path (re-driving loadCmd.valid) until it hits.
        loadCmdPort.ready := !inFlight && xlate.rsp.ready
        when(loadCmdPort.fire) {
          when(isHit) {
            rdSet   := cmdSet
            rdEn    := True
            s1Valid := True
            s1Way   := hitWayIdx
            s1Off   := cmdOff
            s1Size  := loadCmdPort.payload.size
            s1Fault := xlate.rsp.fault
          } otherwise {
            missPaddr := cmdPaddr
            missSet   := cmdSet
            missTag   := cmdTag
            missOff   := cmdOff
            missSize  := loadCmdPort.payload.size
            victimWay := victim(cmdSet)
            arSent    := False
            busy      := True
            goto(REFILL)
          }
        }
      }

      REFILL.whenIsActive {
        busy := True
        // Refill from the PHYSICAL line base (the access was already translated;
        // missPaddr holds the resolved physical address). Under identity == vaddr.
        val lineBase = (missPaddr(31 downto offBits) ## U(0, offBits bits)).asUInt
        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 4 bits)
          axi.ar.payload.len   := U(0, 8 bits)
          axi.ar.payload.size  := U(4, 3 bits)  // 16 bytes
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }
        axi.r.ready := True
        when(axi.r.valid) {
          for (w <- 0 until ways) when(victimWay === U(w, wayBits bits)) {
            wrEn(w)   := True
            wrSet(w)  := missSet
            wrData(w) := axi.r.payload.data
            tagMem(w).write(missSet, missTag)
            valids(w)(missSet) := True
          }
          victim(missSet) := victim(missSet) + 1
          goto(REPLAY)
        }
      }

      REPLAY.whenIsActive {
        busy := True
        rdSet   := missSet
        rdEn    := True
        s1Valid := True
        s1Way   := victimWay
        s1Off   := missOff
        s1Size  := missSize
        s1Fault := False
        goto(IDLE)
      }
    }
  }

  override def loadCmd  = logic.loadCmdPort
  override def loadRsp  = logic.loadRspPort
  override def loadBusy = logic.loadBusyReg
  override def store    = logic.storePort
  override def storeAck = logic.storeAckReg
}
