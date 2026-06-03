package m68k040.cache

import m68k040.services.{FetchService, TranslationService}
import m68k040.frontend.PredecodeWord
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly, Axi4}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesizable VIPT L1 instruction cache.
  *
  * Geometry: 64 sets, 4 ways, 64-byte lines (16 KiB total).
  * AXI read interface: 256-bit (32 B/beat) × 2 beats per line refill.
  * Implements FetchService (cmd/rsp) and resolves TranslationService.
  *
  * FSM: IDLE → REFILL → REPLAY → IDLE.
  */
class IcachePlugin extends FiberPlugin with FetchService {

  // ---- geometry (single source of truth) ----
  private val geo          = CacheGeometry.l1i040
  geo.requireViptSafe(4096, "L1I")         // alias-safety guard (4 KiB pages)
  private val ways         = geo.ways
  private val sets         = geo.sets
  private val tagBits      = geo.tagBits
  private val beatsPerLine = geo.lineBytes / 32   // 256-bit (32 B) beats
  private val setBits      = geo.indexBits
  private val wayBits      = log2Up(ways)

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = 2)

  // ---- logic Area (built during build phase) ----
  val logic = during build new Area {

    // ---- FetchService ports: plain directionless Stream/Flow (NOT slave/master) ----
    val cmdPort       = Stream(FetchCmd())
    val rspPort       = Flow(FetchRsp())
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()

    // ---- resolve TranslationService ----
    val xlate = host[TranslationService]
    val activePc = UInt(32 bits)
    xlate.req.valid      := True
    xlate.req.vpn        := activePc(31 downto 12)
    xlate.req.supervisor := False
    xlate.req.write      := False

    // ---- storage arrays ----
    // Tags + pred: async-read LUTRAM (single write port -> distributed RAM). Kept
    // async so hit/miss is resolved in the accept cycle (a miss starts REFILL with
    // no added latency).
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    val predMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    // Data: synchronous-read BRAM. 2 beats x 64 sets = 128 entries per way.
    val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))
    // Valid bits: register array, cleared by invalidateAll
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    // Round-robin victim pointer per set
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- invalidateAll: priority clear of all valid bits ----
    when(invalidateAll) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
    }

    // ---- miss-state latches ----
    val missPC    = Reg(UInt(32 bits))
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val victimWay = Reg(UInt(wayBits bits))
    val beatCnt   = Reg(UInt(1 bits)) init U(0, 1 bits)
    val arSent    = Reg(Bool()) init False
    val lineReg   = Reg(Bits(512 bits))

    // ---- shared data-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY). The
    // result `dataBeat` is the registered BRAM output, valid the NEXT cycle.
    val dataReadAddr = UInt((setBits + 1) bits)
    val dataReadEn   = Bool()
    dataReadAddr := U(0, (setBits + 1) bits)
    dataReadEn   := False
    val dataBeat = Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))

    // ---- S1 (response-build) pipeline registers ----
    // The cycle after a read is launched, dataBeat is ready: mux by the latched
    // hit-way, lane-select, and register into the rsp output stage below.
    val s1Valid = Reg(Bool()) init False
    val s1Way   = Reg(UInt(wayBits bits))
    val s1Pc    = Reg(UInt(32 bits))
    val s1Fault = Reg(Bool())
    val s1Lane  = Reg(UInt(2 bits))
    val s1Pred  = Reg(Vec(ChunkPredecode(), 4))
    s1Valid := False   // default each cycle; armed in IDLE-hit / REPLAY below

    // ---- rsp output register stage ----
    val rspValidReg = Reg(Bool()) init False
    val rspPcReg    = Reg(UInt(32 bits))
    val rspDataReg  = Reg(Bits(64 bits))
    val rspFaultReg = Reg(Bool())
    val rspPredReg  = Reg(Vec(ChunkPredecode(), 4))

    // ---- window predecode helper ----
    def windowPred(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(16 bits)(pc(5 downto 3))
      val nibs = win.subdivideIn(4 bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    // ---- S1 -> rsp output register (runs every cycle; meaningful when s1Valid) ----
    val s1Beat   = dataBeat(s1Way)
    val s1Window = s1Beat.subdivideIn(64 bits)(s1Lane)
    rspValidReg := s1Valid
    rspPcReg    := s1Pc
    rspDataReg  := s1Window
    rspFaultReg := s1Fault
    rspPredReg  := s1Pred

    // ---- rsp outputs (combinational from the registered stage) ----
    rspPort.valid         := rspValidReg
    rspPort.payload.pc    := rspPcReg
    rspPort.payload.data  := rspDataReg
    rspPort.payload.fault := rspFaultReg
    rspPort.payload.pred  := rspPredReg

    // ---- default output assignments ----
    cmdPort.ready := False
    axi.ar.valid  := False
    axi.ar.payload.assignDontCare()
    axi.r.ready   := False
    activePc      := cmdPort.payload.pc

    // ---- hit detection (combinational, from cmdPort.pc + translation) ----
    val idlePc    = cmdPort.payload.pc
    val idleSet   = idlePc(11 downto 6)
    val idleTag   = xlate.rsp.ppn
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := valids(w)(idleSet) && (tagMem(w).readAsync(idleSet) === idleTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val idleBeatSel = idlePc(5)
    val idleLaneIdx = idlePc(4 downto 3)
    val idleReadAddr  = (idleSet ## idleBeatSel).asUInt
    val idlePredEntry = Vec(predMem.map(_.readAsync(idleSet)))

    // Single-in-flight: do not accept a new cmd while a hit response is draining
    // (S1 or the rsp register). Costs nothing — the consumer is single-outstanding.
    val inFlight = s1Valid || rspValidReg

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      val PREDECODE = new State
      val REPLAY    = new State

      // ----- IDLE: accept; hit -> arm S1 read, miss -> latch + refill -----
      IDLE.whenIsActive {
        activePc      := cmdPort.payload.pc
        cmdPort.ready := !inFlight

        when(cmdPort.fire) {
          when(isHit) {
            // Arm S1: launch the data BRAM read; latch control for next-cycle mux.
            dataReadAddr := idleReadAddr
            dataReadEn   := True
            s1Valid := True
            s1Way   := hitWayIdx
            s1Pc    := idlePc
            s1Fault := xlate.rsp.fault
            s1Lane  := idleLaneIdx
            s1Pred  := windowPred(idlePredEntry(hitWayIdx), idlePc)
          } otherwise {
            missPC    := idlePc
            missSet   := idleSet
            missTag   := idleTag
            victimWay := victim(idleSet)
            beatCnt   := U(0, 1 bits)
            arSent    := False
            goto(REFILL)
          }
        }
      }

      // ----- REFILL: issue AXI AR; collect 2 R beats into dataMem -----
      REFILL.whenIsActive {
        activePc := missPC
        val lineBase = missPC & ~U(63, 32 bits)

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 2 bits)
          axi.ar.payload.len   := U(1, 8 bits)
          axi.ar.payload.size  := U(5, 3 bits)
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }

        axi.r.ready := True
        when(axi.r.valid) {
          val writeAddr = (missSet ## beatCnt).asUInt
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              dataMem(w).write(writeAddr, axi.r.payload.data)
            }
          }
          when(beatCnt === U(0, 1 bits)) {
            lineReg(255 downto 0)   := axi.r.payload.data
          } otherwise {
            lineReg(511 downto 256) := axi.r.payload.data
          }
          beatCnt := beatCnt + 1
          when(axi.r.payload.last) { goto(PREDECODE) }
        }
      }

      // ----- PREDECODE: classify line, write predMem + tag/valid -----
      PREDECODE.whenIsActive {
        activePc := missPC
        val words  = lineReg.subdivideIn(16 bits)
        val chunks = Vec(words.map(w => PredecodeWord.classify(w)))
        val packed = chunks.asBits
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            predMem(w).write(missSet, packed)
            tagMem(w).write(missSet, missTag)
            valids(w)(missSet) := True
          }
        }
        victim(missSet) := victim(missSet) + 1
        goto(REPLAY)
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        activePc := missPC
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayPredEntry = Vec(predMem.map(_.readAsync(missSet)))

        dataReadAddr := replayReadAddr
        dataReadEn   := True
        s1Valid := True
        s1Way   := victimWay
        s1Pc    := missPC
        s1Fault := xlate.rsp.fault
        s1Lane  := missPC(4 downto 3)
        s1Pred  := windowPred(replayPredEntry(victimWay), missPC)

        goto(IDLE)
      }
    }

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
