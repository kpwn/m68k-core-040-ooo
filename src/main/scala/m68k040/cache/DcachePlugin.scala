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

    // ---- translation (load path only; D-side TLB) ----
    val xlate = host[DTranslationService]
    val xlateVpn = UInt(20 bits)
    xlate.req.vpn        := xlateVpn
    xlate.req.supervisor := False
    xlateVpn := loadCmdPort.payload.vaddr(31 downto 12)

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
    val missVaddr = Reg(UInt(32 bits))
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

    // ---- load hit detection (combinational from cmd vaddr + translation) ----
    val cmdVaddr = loadCmdPort.payload.vaddr
    val cmdSet   = cmdVaddr(offBits + setBits - 1 downto offBits)
    val cmdOff   = cmdVaddr(offBits - 1 downto 0)
    val cmdPaddrTag = xlate.rsp.ppn ## cmdVaddr(11 downto offBits + setBits)  // high tag bits
    // physical tag = paddr[31:offBits+setBits]; paddr = ppn(20) ## vaddr[11:0].
    // tagBits=21, of which the top (32-12)=20 are ppn and the low (12-offBits-setBits)=1 are vaddr.
    val cmdTag = (xlate.rsp.ppn ## cmdVaddr(11 downto offBits + setBits)).asUInt
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

    // ---- STORE write-through ----
    // On store-accept (a Flow pulse): RMW the hit line (combinational BRAM write
    // this cycle) and latch the merged 128-bit beat + strobe + paddr into regs.
    // A small store FSM then drives the AXI write (aw + w, single beat) holding
    // valid until both handshake. Single-outstanding in this slice; the LS pipe
    // does not present a second store while one is draining.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True

    val stSet = storePort.payload.paddr(offBits + setBits - 1 downto offBits)
    val stOff = storePort.payload.paddr(offBits - 1 downto 0)
    val stTag = storePort.payload.paddr(31 downto offBits + setBits)
    val stHit = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stHit(w) := valids(w)(stSet) && (tagMem(w).readAsync(stSet) === stTag)
    // Aligned/probe path derives the merge from {data,size,offset}; a SPLIT store
    // slot supplies an explicit line-relative strobe + 128-bit line-aligned data.
    val mergeData = Mux(storePort.payload.useStrb,
      storePort.payload.lineData,
      DcacheByteLane.storeData(stOff, storePort.payload.size, storePort.payload.data))
    val mergeStrb = Mux(storePort.payload.useStrb,
      storePort.payload.strb,
      DcacheByteLane.storeStrb(stOff, storePort.payload.size))
    val mrgBytes  = mergeData.subdivideIn(8 bits)

    when(storePort.valid) {
      // RMW the hit way's line (only the strobed bytes). Per-way (Scala-indexed).
      for (w <- 0 until ways) when(stHit(w)) {
        val cur      = dataMem(w).readAsync(stSet)
        val curBytes = cur.subdivideIn(8 bits)
        val newBytes = Vec(Bits(8 bits), 16)
        for (i <- 0 until 16) newBytes(i) := Mux(mergeStrb(i), mrgBytes(i), curBytes(i))
        wrEn(w)   := True
        wrSet(w)  := stSet
        wrData(w) := newBytes.asBits
      }
      // latch for the write-through beat
      stMergeReg := mergeData
      stStrbReg  := mergeStrb
      stAddrReg  := (storePort.payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt
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
        loadCmdPort.ready := !inFlight
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
            missVaddr := cmdVaddr
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
        xlateVpn := missVaddr(31 downto 12)
        val lineBase = (missVaddr(31 downto offBits) ## U(0, offBits bits)).asUInt
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
