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
  *   - LOAD: VIPT 2-cycle read (S0 launch BRAM tag+data read + translate; S1
  *     tag-compare against the REGISTERED tag-read, way-mux, byte-lane extract).
  *     Miss -> single-beat refill -> replay.
  *   - STORE: physical (SQ already translated). RMW the line if it hits; ALWAYS
  *     issue an AXI write (write-through). No allocate on a miss.
  *
  * FMax (P0.1): ALL D-cache RAM reads are SYNCHRONOUS so Vivado infers BRAM
  * (RAMB36) instead of async distributed-RAM (LUTRAM / RAMD64E):
  *   - `dataMem` + `tagMem` are write + readSync ONLY (no readAsync) per way ->
  *     simple-dual-port BRAM (1 write + 1 sync-read).
  *   - The load data-read and the store-RMW old-line-read SHARE the one sync-read
  *     port (the cache is single-outstanding per the LS pipe; an explicit load>store
  *     arbiter defers the store-read by a cycle on the rare overlap).
  *   - Hit/miss is resolved ONE CYCLE LATER (in S1, against the registered tag-read
  *     vs the registered ppn-tag) — latency-agnostic; lock-step absorbs the +1.
  * Valids: register array. Victim: register array. */
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

    // ---- storage (sync-read BRAM: write + readSync ONLY, no readAsync) ----
    val dataMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- single muxed data/tag write port per way (refill + store-write) ----
    val wrEn    = Vec.fill(ways)(False)
    val wrSet   = Vec.fill(ways)(U(0, setBits bits))
    val wrData  = Vec.fill(ways)(B(0, 128 bits))
    val wrTagEn = Vec.fill(ways)(False)             // tag write (refill only)
    val wrTag   = Vec.fill(ways)(U(0, tagBits bits))
    for (w <- 0 until ways) {
      dataMem(w).write(wrSet(w), wrData(w), wrEn(w))
      tagMem(w).write(wrSet(w), wrTag(w), wrTagEn(w))
    }

    // ---- shared synchronous read port per way (S0 launch -> S1 result) ----
    // Drives BOTH dataMem.readSync (line) AND tagMem.readSync (hit-detect) at the
    // SAME set address — the load and the store-RMW old-line read always read the
    // same single set, and they are arbitrated load>store onto this one port below
    // so each Mem has exactly one sync-read (=> simple-dual-port BRAM).
    val rdSet = UInt(setBits bits)
    val rdEn  = Bool()
    rdSet := U(0, setBits bits)
    rdEn  := False
    val rdData = Vec(dataMem.map(_.readSync(rdSet, rdEn)))
    val rdTag  = Vec(tagMem.map(_.readSync(rdSet, rdEn)))

    // ---- miss-state latches ----
    val missPaddr = Reg(UInt(32 bits))   // physical addr of the missing line (AXI refill base)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val missOff   = Reg(UInt(offBits bits))
    val missSize  = Reg(Size())
    val victimWay = Reg(UInt(wayBits bits))
    val arSent    = Reg(Bool()) init False

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

    // ---- load index/tag from cmd vaddr + PRE-TRANSLATED paddr ----
    // FMax: the physical tag comes from the requester's REGISTERED `loadCmd.paddr`
    // (translate-at-execute already resolved it into a register), NOT from the live
    // DTLB lookup `xlate.rsp.ppn`. VIPT: index on the page-invariant vaddr set bits;
    // tag on the physical bits. Hit-detect itself is deferred to S1 (registered).
    val cmdVaddr = loadCmdPort.payload.vaddr
    val cmdPaddr = loadCmdPort.payload.paddr
    val cmdSet   = cmdVaddr(offBits + setBits - 1 downto offBits)
    val cmdOff   = cmdVaddr(offBits - 1 downto 0)
    val cmdTag   = cmdPaddr(31 downto offBits + setBits)   // physical tag = paddr[31:offBits+setBits]

    // ---- LOAD S1 (registered hit-detect + response build) pipeline registers ----
    // The cycle after a load is accepted, the BRAM tag-read (rdTag) + data-read
    // (rdData) are valid. S1 resolves the hit against the REGISTERED ppn-tag + set,
    // way-muxes the data, lane-extracts, and (on a miss) starts the refill. The +1
    // cycle to start a refill is latency-agnostic (lock-step absorbs it).
    val ldS1Valid = RegInit(False)       // a load-read was launched into S1
    val ldS1Set   = Reg(UInt(setBits bits))
    val ldS1Tag   = Reg(UInt(tagBits bits))
    val ldS1Off   = Reg(UInt(offBits bits))
    val ldS1Size  = Reg(Size())
    val ldS1Fault = Reg(Bool())
    val ldS1Paddr = Reg(UInt(32 bits))   // physical addr (refill base on a miss)
    ldS1Valid := False                   // default; armed on an accept below

    // S1 hit-detect against the REGISTERED tag-read vs the REGISTERED ppn-tag. The
    // compare's inputs are both registered (BRAM tag-read output + the latched ppn-
    // tag), so the response is driven COMBINATIONALLY in S1 — the SAME load latency
    // as the old async-LUTRAM hit-detect (1 cycle after accept), but the arc is now
    // a short registered-compare instead of the readAsync->compare->dataMem cone.
    val ldS1HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      ldS1HitVec(w) := valids(w)(ldS1Set) && (rdTag(w) === ldS1Tag)
    val ldS1Hit     = ldS1HitVec.orR
    val ldS1HitWay  = OHToUInt(ldS1HitVec)
    val ldS1Line    = rdData(ldS1HitWay)
    val ldS1Resp    = ldS1Valid && (ldS1Hit || ldS1Fault)

    loadRspPort.valid         := ldS1Resp
    loadRspPort.payload.data  := DcacheByteLane.extract(ldS1Line, ldS1Off, ldS1Size)
    loadRspPort.payload.line  := ldS1Line
    loadRspPort.payload.fault := ldS1Fault

    // ---- STORE write-through (PIPELINED RMW: S0 latch / S1 read / S2 merge+write) ----
    // FMax: the store RMW (old line readAsync + 16-lane byte-merge + write) used to
    // be a combinational cycle fronted by the far-placed exc-FSM/DTLB cone. With all
    // reads SYNC the old line + hit-tag are read from BRAM:
    //   store-S0 (on storePort.valid): latch the raw store payload into regs PHYSICALLY
    //     at the cache (a short route ending at a flop).
    //   store-S1 (next cycle): launch the SHARED sync read (old line + hit-tag) at the
    //     store's set — UNLESS the load FSM is using the read port this cycle (load
    //     priority); on conflict the store holds in S1 and retries next cycle.
    //   store-S2 (after the read lands): resolve the hit (registered tag-read), merge
    //     the registered old line with the store bytes, drive the dataMem write +
    //     latch the AXI write-through beat.
    // Single-outstanding: the producer never presents a 2nd store before this one's
    // AXI B (storeAck) lands, so S0/S1/S2 never overlap a second store. valids/tagMem
    // cannot change between S0 and S2 (no concurrent refill into the same flow), so
    // the S2 hit-detect is correct.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True

    // ---- store-S0: PURE payload latch (the cache-boundary flop) ----
    val s0Valid   = RegInit(False)
    val s0Payload = Reg(DStoreCmd())
    s0Valid := False  // default; armed on a store-accept below
    when(storePort.valid) {
      s0Valid   := True
      s0Payload := storePort.payload
    }

    // ---- store-S1: launch the shared sync read (old line + hit-tag) ----
    // Decoded off the REGISTERED S0 payload. The read launches onto the shared
    // rdSet/rdEn port only when the load FSM is NOT using it (arbiter below); on a
    // conflict the store stays in S1 (s1Pending) and retries next cycle.
    val stS1Valid   = RegInit(False)      // a store is in S1 awaiting its read launch
    val stS1Payload = Reg(DStoreCmd())
    val stS1Set     = stS1Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS1Off     = stS1Payload.paddr(offBits - 1 downto 0)
    val stS1Tag     = stS1Payload.paddr(31 downto offBits + setBits)
    stS1Valid := False  // default; armed below when S0 advances / S1 holds

    // ---- store-S2: registered old line + hit + merge + write ----
    val stS2Valid   = RegInit(False)      // the store read has landed; merge+write now
    val stS2Payload = Reg(DStoreCmd())
    val stS2Set     = stS2Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS2Off     = stS2Payload.paddr(offBits - 1 downto 0)
    val stS2Tag     = stS2Payload.paddr(31 downto offBits + setBits)
    stS2Valid := False  // default; armed when S1's read launches

    // S0 -> S1: advance the latched payload into S1.
    when(s0Valid) {
      stS1Valid   := True
      stS1Payload := s0Payload
    }

    // ---- LOAD FSM (drives the shared read port with PRIORITY over the store) ----
    val fsm = new StateMachine {
      val IDLE   = new State with EntryPoint
      val REFILL = new State
      val REPLAY = new State

      // In-flight gate: do not accept a new load while one is resolving in S1 (its
      // combinational response is live this same cycle). Single-outstanding on the
      // load side — identical to the old `s1Valid || loadRspPort.valid` accounting.
      val inFlight = ldS1Valid

      // Whether the load FSM uses the shared read port THIS cycle (set in the
      // load-accept and REPLAY arms below). The store arbiter reads this to defer.
      val loadUsesPort = False

      IDLE.whenIsActive {
        busy := False
        // Accept a load only when translation is RESOLVED this cycle (TLB hit or
        // identity). On a DTLB miss `rsp.ready` is False while the walker runs, so
        // the load is not accepted and the LS EU stays on its existing single-
        // outstanding back-pressure path (re-driving loadCmd.valid) until it hits.
        loadCmdPort.ready := !inFlight && xlate.rsp.ready
        when(loadCmdPort.fire) {
          // Launch the BRAM tag+data read for this set; resolve hit/miss in S1.
          rdSet        := cmdSet
          rdEn         := True
          loadUsesPort := True
          ldS1Valid    := True
          ldS1Set      := cmdSet
          ldS1Tag      := cmdTag
          ldS1Off      := cmdOff
          ldS1Size     := loadCmdPort.payload.size
          ldS1Fault    := xlate.rsp.fault
          ldS1Paddr    := cmdPaddr
        }
        // S1 resolution of a launched load read. A hit/fault drives the response
        // combinationally (ldS1Resp above). A miss starts the refill.
        when(ldS1Valid && !ldS1Hit && !ldS1Fault) {
          // Miss: latch miss-state and start the refill (the +1-cycle deferral
          // relative to the old async hit-detect is latency-agnostic).
          missPaddr := ldS1Paddr
          missSet   := ldS1Set
          missTag   := ldS1Tag
          missOff   := ldS1Off
          missSize  := ldS1Size
          victimWay := victim(ldS1Set)
          arSent    := False
          busy      := True
          goto(REFILL)
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
            wrEn(w)    := True
            wrSet(w)   := missSet
            wrData(w)  := axi.r.payload.data
            wrTagEn(w) := True
            wrTag(w)   := missTag
            valids(w)(missSet) := True
          }
          victim(missSet) := victim(missSet) + 1
          goto(REPLAY)
        }
      }

      REPLAY.whenIsActive {
        busy := True
        // Re-launch the read for the just-filled line; resolve as a guaranteed hit
        // into the response register one cycle later via the ldS1 path.
        rdSet        := missSet
        rdEn         := True
        loadUsesPort := True
        ldS1Valid    := True
        ldS1Set      := missSet
        ldS1Tag      := missTag
        ldS1Off      := missOff
        ldS1Size     := missSize
        ldS1Fault    := False
        ldS1Paddr    := missPaddr
        goto(IDLE)
      }
    }

    // ---- store read-port arbiter: load>store; defer the store-S1 read on conflict ----
    // The store launches its shared read ONLY when the load FSM isn't using the port
    // this cycle. On a conflict the store holds in S1 (re-armed below) and retries.
    when(stS1Valid) {
      when(!fsm.loadUsesPort) {
        rdSet       := stS1Set
        rdEn        := True
        stS2Valid   := True
        stS2Payload := stS1Payload
      } otherwise {
        // Port busy: hold in S1, retry next cycle.
        stS1Valid   := True
      }
    }

    // ---- store-S2: hit-detect (registered tag-read) + merge old line + write ----
    // Placed BEFORE the refill write in the FSM ordering is preserved by driving the
    // SAME wrEn/wrSet/wrData vectors: a same-cycle refill write (in REFILL) is the
    // LAST assignment and overrides this store write (refill has priority).
    // Single-outstanding guarantees they never actually collide.
    val stS2HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stS2HitVec(w) := valids(w)(stS2Set) && (rdTag(w) === stS2Tag)
    // Aligned/probe path derives the merge from {data,size,offset}; a SPLIT store
    // slot supplies an explicit line-relative strobe + 128-bit line-aligned data.
    val mergeData = Mux(stS2Payload.useStrb,
      stS2Payload.lineData,
      DcacheByteLane.storeData(stS2Off, stS2Payload.size, stS2Payload.data))
    val mergeStrb = Mux(stS2Payload.useStrb,
      stS2Payload.strb,
      DcacheByteLane.storeStrb(stS2Off, stS2Payload.size))
    val stS2MrgBytes = mergeData.subdivideIn(8 bits)
    when(stS2Valid) {
      for (w <- 0 until ways) when(stS2HitVec(w)) {
        val curBytes = rdData(w).subdivideIn(8 bits)
        val newBytes = Vec(Bits(8 bits), 16)
        for (i <- 0 until 16) newBytes(i) := Mux(mergeStrb(i), stS2MrgBytes(i), curBytes(i))
        wrEn(w)   := True
        wrSet(w)  := stS2Set
        wrData(w) := newBytes.asBits
      }
      // latch the write-through beat payload (AXI aw/w fire after S2).
      stMergeReg := mergeData
      stStrbReg  := mergeStrb
      stAddrReg  := (stS2Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt  // line-aligned
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
  }

  override def loadCmd  = logic.loadCmdPort
  override def loadRsp  = logic.loadRspPort
  override def loadBusy = logic.loadBusyReg
  override def store    = logic.storePort
  override def storeAck = logic.storeAckReg
}
