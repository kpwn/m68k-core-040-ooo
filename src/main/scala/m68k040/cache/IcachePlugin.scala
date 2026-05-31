package m68k040.cache

import m68k040.services.{FetchService, TranslationService}
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

  // ---- geometry constants (Scala-time) ----
  private val ways         = 4
  private val sets         = 64
  private val tagBits      = 20
  private val beatsPerLine = 2     // 64-byte line / 32 bytes per beat
  private val setBits      = log2Up(sets)  // 6
  private val wayBits      = log2Up(ways)  // 2

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = 2)

  // ---- logic Area (built during build phase) ----
  val logic = during build new Area {

    // ---- ports exposed to testbench / parent component ----
    val cmdPort       = slave(Stream(FetchCmd()))
    val rspPort       = master(Flow(FetchRsp()))
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()

    // ---- resolve TranslationService ----
    val xlate = host[TranslationService]

    // We always present an activePc to translation (combinational).
    val activePc = UInt(32 bits)
    xlate.req.vpn        := activePc(31 downto 12)
    xlate.req.supervisor := False

    // ---- storage arrays ----
    // Tags: async-read LUTRAM
    val tagMem = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    // Data: 2 beats × 64 sets = 128 entries per way; async-read
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
    // Track whether the AXI AR has been accepted (to deassert ar.valid after handshake)
    val arSent    = Reg(Bool()) init False

    // Registered fetch response: rsp.valid is a clean 1-cycle pulse the cycle
    // AFTER a hit is accepted (and after REPLAY for a miss), uniform latency.
    // Registering keeps the tag-compare + data-mux path off the consumer's cycle (FMax).
    val rspValidReg = Reg(Bool()) init False
    val rspPcReg    = Reg(UInt(32 bits))
    val rspDataReg  = Reg(Bits(64 bits))
    val rspFaultReg = Reg(Bool())

    // ---- read-data mux helpers (Scala-level; hardware MuxOH at elaboration) ----
    // Read all ways and select with OHToUInt / hardware MUX.
    def readDataMem(readAddr: UInt, selWay: UInt): Bits = {
      val perWay = Vec(dataMem.map(_.readAsync(readAddr)))
      perWay(selWay)
    }

    // ---- default output assignments ----
    cmdPort.ready  := False
    rspValidReg           := False                 // pulse: default low each cycle
    rspPort.valid         := rspValidReg
    rspPort.payload.pc    := rspPcReg
    rspPort.payload.data  := rspDataReg
    rspPort.payload.fault := rspFaultReg
    axi.ar.valid   := False
    axi.ar.payload.assignDontCare()
    axi.r.ready    := False

    // activePc default (overridden in FSM states)
    activePc := cmdPort.payload.pc

    // ---- hit detection (always-on combinational, needed in IDLE) ----
    // These are derived from cmdPort.pc and translation result.
    val idlePc    = cmdPort.payload.pc
    val idleSet   = idlePc(11 downto 6)
    val idleTag   = xlate.rsp.ppn    // comes from activePc translation

    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := valids(w)(idleSet) && (tagMem(w).readAsync(idleSet) === idleTag)

    val isHit     = hitVec.orR
    val hitWayIdx = OHToUInt(hitVec)

    val idleBeatSel  = idlePc(5)
    val idleLaneIdx  = idlePc(4 downto 3)
    val idleReadAddr = (idleSet ## idleBeatSel).asUInt
    val idleBeatData = readDataMem(idleReadAddr, hitWayIdx)
    val idleWindow   = idleBeatData.subdivideIn(64 bits)(idleLaneIdx)

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE   = new State with EntryPoint
      val REFILL = new State
      val REPLAY = new State

      // ------------------------------------------------------------------
      // IDLE: accept fetch commands; hit → respond immediately, miss → latch
      // ------------------------------------------------------------------
      IDLE.whenIsActive {
        activePc      := cmdPort.payload.pc
        cmdPort.ready := True

        when(cmdPort.fire) {
          when(isHit) {
            rspValidReg := True                    // registered: pulses next cycle
            rspPcReg    := idlePc
            rspDataReg  := idleWindow
            rspFaultReg := xlate.rsp.fault
            // remain in IDLE
          } otherwise {
            // Miss: latch all miss state and go refill
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

      // ------------------------------------------------------------------
      // REFILL: issue AXI AR; collect 2 R beats into dataMem
      // ------------------------------------------------------------------
      REFILL.whenIsActive {
        activePc := missPC

        val lineBase = missPC & ~U(63, 32 bits)

        // Drive AR only until it has been accepted
        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 2 bits)
          axi.ar.payload.len   := U(1, 8 bits)   // 2 beats (len = n-1)
          axi.ar.payload.size  := U(5, 3 bits)   // log2(32) = 5
          axi.ar.payload.burst := Axi4.burst.INCR

          when(axi.ar.ready) {
            arSent := True
          }
        }

        axi.r.ready := True

        when(axi.r.valid) {
          val writeAddr = (missSet ## beatCnt).asUInt
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              dataMem(w).write(writeAddr, axi.r.payload.data)
            }
          }
          beatCnt := beatCnt + 1

          when(axi.r.payload.last) {
            for (w <- 0 until ways) {
              when(victimWay === U(w, wayBits bits)) {
                tagMem(w).write(missSet, missTag)
                valids(w)(missSet) := True
              }
            }
            victim(missSet) := victim(missSet) + 1
            goto(REPLAY)
          }
        }
      }

      // ------------------------------------------------------------------
      // REPLAY: deliver the just-filled line for the latched PC
      // ------------------------------------------------------------------
      REPLAY.whenIsActive {
        activePc := missPC

        val replayBeatSel  = missPC(5)
        val replayLaneIdx  = missPC(4 downto 3)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayBeatData = readDataMem(replayReadAddr, victimWay)
        val replayWindow   = replayBeatData.subdivideIn(64 bits)(replayLaneIdx)

        rspValidReg := True                        // registered: pulses next cycle (in IDLE)
        rspPcReg    := missPC
        rspDataReg  := replayWindow
        rspFaultReg := xlate.rsp.fault

        goto(IDLE)
      }
    }

    // FetchService accessors (also used by plugin-level overrides below)
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
