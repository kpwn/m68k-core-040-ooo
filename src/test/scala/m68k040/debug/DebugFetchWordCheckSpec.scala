package m68k040.debug

import m68k040.M68kSim
import m68k040.frontend.DecodePacket
import m68k040.services.DecodeFeedService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class DebugFetchWordCheckSpec extends AnyFunSuite {
  class FeedDriver extends FiberPlugin with DecodeFeedService {
    val logic = during build new Area {
      val validIn = in Bool()
      val readyIn = in Bool()
      val secondIn = in Bool()
      val pcIn = in Vec(UInt(32 bits), 2)
      val wordIn = in Vec(Bits(16 bits), 2)
      val faultIn = in Bits(2 bits)
      val stream = Stream(Vec(DecodePacket(), 2))
      stream.valid := validIn; stream.ready := readyIn
      for (i <- 0 until 2) {
        val p = stream.payload(i)
        p.flatten.filterNot(x => (x eq p.pc) || (x eq p.words(0)) || (x eq p.fault))
          .foreach(x => x.assignFromBits(B(0, x.getBitsWidth bits)))
        p.pc := pcIn(i); p.words(0) := wordIn(i); p.fault := faultIn(i)
      }
    }
    override def feed = logic.stream
    override def slot1Valid = logic.secondIn
  }
  class Dut(history: Int = 32) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val source = new FeedDriver
    val dbg = new DebugCtrlPlugin(stage = 5, porCycles = 4, historyDepth = history)
    db.on { host.asHostOf(Seq(source, dbg)) }
    def axi = dbg.logic.dbgAxi
  }
  def idle(d: Dut): Unit = {
    DbgAxiDriver.idle(d.axi); d.dbg.logic.initDoneSeen #= false
    d.source.logic.validIn #= false; d.source.logic.readyIn #= true
    d.source.logic.secondIn #= false; d.source.logic.faultIn #= 0
    for (i <- 0 until 2) { d.source.logic.pcIn(i) #= 0; d.source.logic.wordIn(i) #= 0 }
  }

  test("fetch word evidence is qualified, first-hit sticky, reset-safe and readable through AXI") {
    M68kSim().compile(new Dut).doSim { d =>
      SimTimeout(100000)
      val cd = d.clockDomain; cd.forkStimulus(10); idle(d); cd.waitSampling(20)
      def rd(off: Int) = { println(f"[fetch-check] read 0x$off%x"); DbgAxiDriver.read(d.axi, cd, off) }
      def wr(off: Int, value: Long) = { println(f"[fetch-check] write 0x$off%x = 0x$value%x"); DbgAxiDriver.write(d.axi, cd, off, value) }
      val pc = 0x4080e2a2L
      def packet(slot: Int, word: Int, at: Long = pc, fault: Boolean = false,
                 ready: Boolean = true, second: Boolean = true): Unit = {
        cd.waitFallingEdge()
        for (i <- 0 until 2) { d.source.logic.pcIn(i) #= 0; d.source.logic.wordIn(i) #= 0 }
        d.source.logic.pcIn(slot) #= at; d.source.logic.wordIn(slot) #= word
        d.source.logic.faultIn #= (if (fault) 1 << slot else 0)
        d.source.logic.secondIn #= second; d.source.logic.readyIn #= ready
        d.source.logic.validIn #= true
        cd.waitSampling(); sleep(1); d.source.logic.validIn #= false
        cd.waitSampling(4)
      }
      assert((rd(DebugRegMap.OFF_FEATURES) & (1L << 25)) != 0)
      wr(DebugRegMap.OFF_FETCH_CHECK_PC, pc)
      wr(DebugRegMap.OFF_FETCH_CHECK_WORD, 0x2228)
      wr(DebugRegMap.OFF_FETCH_CHECK_CTL, 3)
      packet(0, 0xa08d, ready = false)
      packet(0, 0xa08d, at = pc + 2)
      packet(0, 0xa08d, fault = true)
      packet(1, 0xa08d, second = false)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 0)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 1)
      packet(0, 0x2228)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 1)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 1)
      packet(1, 0xa08d)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 7)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_HIT_PC) == pc)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_HIT_WORD) == 0x2228a08dL)
      packet(0, 0x4e75)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 3)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_HIT_WORD) == 0x2228a08dL)
      DbgAxiDriver.write(d.axi, cd, DebugRegMap.OFF_FETCH_CHECK_CTL, 3, strb = 0)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 7)
      wr(DebugRegMap.OFF_FETCH_CHECK_CTL, 0)
      packet(0, 0xa08d)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 3)
      wr(DebugRegMap.OFF_FETCH_CHECK_CTL, 3)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 1)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 0)
      packet(0, 0xa08d)
      cd.assertReset(); sleep(50); cd.deassertReset(); cd.waitSampling(5)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 1)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_PC) == pc)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_WORD) == 0x2228)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_SEEN) == 0)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_HIT_WORD) == 0)
      wr(DebugRegMap.OFF_DBG_RESET_CTL, 1)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_CTL) == 0)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_PC) == 0)
      assert(rd(DebugRegMap.OFF_FETCH_CHECK_WORD) == 0)
    }
  }

  test("disabled optional history makes fetch check RAZ WI and clears its capability") {
    M68kSim().compile(new Dut(0)).doSim { d =>
      SimTimeout(100000)
      val cd = d.clockDomain; cd.forkStimulus(10); idle(d); cd.waitSampling(20)
      assert((DbgAxiDriver.read(d.axi, cd, DebugRegMap.OFF_FEATURES) & (1L << 25)) == 0)
      for (off <- DebugRegMap.OFF_FETCH_CHECK_CTL to DebugRegMap.OFF_FETCH_CHECK_SEEN by 4) {
        DbgAxiDriver.write(d.axi, cd, off, 0xffffffffL)
        assert(DbgAxiDriver.read(d.axi, cd, off) == 0)
      }
    }
  }
}
