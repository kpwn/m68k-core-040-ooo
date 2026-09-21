package m68k040.debug

import m68k040.M68kSim
import m68k040.services._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class PerfDetailSpec extends AnyFunSuite {
  class Source extends FiberPlugin with RobPerfDetailService with DispatchPerfDetailService {
    private var r: Bits = null
    private var d: Bits = null
    during setup { r = in(Bits(23 bits)); d = in(Bits(6 bits)) }
    def robPerfEvents = Some(r)
    def robPerfRetirement = None
    def dispatchPerfEvents = Some(d)
  }
  class CsrDut(enabled: Boolean) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val source = new Source
    val dbg = new DebugCtrlPlugin(stage = 2, porCycles = 4, detailedPerf = enabled)
    db.on { host.asHostOf(Seq(source, dbg)) }
  }
  for (enabled <- Seq(false, true)) test(s"detail CSR capability, clear, freeze and exact window; enabled=$enabled") {
    M68kSim().compile(new CsrDut(enabled)).doSim { d =>
      import DebugRegMap._
      val cd = d.clockDomain; cd.forkStimulus(10)
      val bus = d.dbg.logic.dbgAxi
      DbgAxiDriver.idle(bus); d.dbg.logic.initDoneSeen #= false
      d.source.robPerfEvents.get #= 1; d.source.dispatchPerfEvents.get #= 1
      cd.waitSampling(30)
      def rd(a: Int) = DbgAxiDriver.read(bus, cd, a) & 0xffffffffL
      def wr(v: Long) = DbgAxiDriver.write(bus, cd, OFF_PERF_CTL, v)
      assert(rd(OFF_PERF_DETAIL_CAP) == (if (enabled) 0xd1011706L else 0L))
      wr(3); cd.waitSampling(40); wr(0); cd.waitSampling(5)
      val cycles = rd(OFF_PERF_CYCLE_LO)
      for (n <- 0 until 29) {
        assert(rd(OFF_PERF_DETAIL_BASE + n * 4) ==
          (if (enabled && (n == 0 || n == 23)) cycles else 0L))
      }
      cd.waitSampling(20)
      assert(rd(OFF_PERF_DETAIL_BASE) == (if (enabled) cycles else 0L))
      wr(1); cd.waitSampling(5)
      for (n <- 0 until 29) assert(rd(OFF_PERF_DETAIL_BASE + n * 4) == 0L)
      wr(2); cd.waitSampling(10); wr(0); cd.waitSampling(5)
      assert(rd(OFF_PERF_DETAIL_BASE) == (if (enabled) rd(OFF_PERF_CYCLE_LO) else 0L))
    }
  }
  class Dut extends Component {
    val state = in(RobPerfState())
    val dispatch = in(Bits(4 bits))
    val admissionReady = in Bool()
    val robEvents = out(Bits(PerfDetail.RobCount bits))
    val dispatchEvents = out(Bits(PerfDetail.DispatchCount bits))
    robEvents := PerfDetail.robEvents(state)
    dispatchEvents := PerfDetail.dispatchEvents(dispatch(0), dispatch(1), dispatch(2), dispatch(3), admissionReady)
  }
  test("exclusive ROB/dispatch partitions and overlapping retirement diagnostics") {
    M68kSim().compile(new Dut).doSim { d =>
      val rng = new scala.util.Random(0x68040)
      d.admissionReady #= true
      for (_ <- 0 until 4096) {
        val b = Array.fill(17)(rng.nextBoolean())
        val kind = rng.nextInt(8)
        val fields = Seq(d.state.nonempty, d.state.second, d.state.full,
          d.state.retire0, d.state.retire1, d.state.complete0, d.state.complete1,
          d.state.flushing, d.state.halted, d.state.exceptionActive,
          d.state.last0, d.state.last1, d.state.alone0, d.state.alone1,
          d.state.redirect, d.state.mispredict)
        // Enforce valid retirement relationships, while leaving stale payloads random.
        b(3) = b(3) && b(0); b(4) = b(4) && b(3) && b(1)
        fields.zipWithIndex.foreach { case (f, n) => f #= b(n) }
        d.state.kind #= kind
        val dispatch = rng.nextInt(16); d.dispatch #= dispatch
        sleep(1)
        val e = d.robEvents.toBigInt
        val primary = if (!b(0)) 0 else if (b(3)) { if (b(4)) 2 else 1 }
          else if (b(7)) 3 else if (b(8)) 4 else if (b(9)) 5
          else if (!b(5)) kind match {
            case 1 => 6; case 2 => 7; case 3 | 5 => 8; case 4 => 9; case _ => 10
          } else 11
        assert((e & 0xfff) == (BigInt(1) << primary))
        val macros = (if (b(3) && b(10)) 1 else 0) + (if (b(4) && b(11)) 1 else 0)
        assert(((e >> 12) & 7) == (if (b(3)) BigInt(1) << macros else BigInt(0)))
        val slot1 = if (!b(1)) 0 else if (!b(6)) 1 else if (b(12) || b(13)) 2 else 3
        assert(((e >> 15) & 15) == (if (b(3) && !b(4)) BigInt(1) << slot1 else BigInt(0)))
        assert(e.testBit(19) == (b(14) && kind == 5))
        assert(e.testBit(20) == (b(14) && kind != 5))
        assert(e.testBit(21) == (b(0) && b(15) && !b(3)))
        assert(e.testBit(22) == b(2))
        val expected = if ((dispatch & 1) == 0) 0 else if ((dispatch & 2) == 0) 1
          else if ((dispatch & 4) == 0) 2 else if ((dispatch & 8) == 0) 3 else 4
        assert((d.dispatchEvents.toInt & 31) == (1 << expected))
        assert(((d.dispatchEvents.toInt & 32) != 0) == ((dispatch & 7) == 1))
      }
      // Extra admission blocks may leave the old reason partition empty, but
      // must not fabricate accepts or misattribute a stall to the IQ/ROB.
      d.admissionReady #= false
      for (dispatch <- 0 until 16) {
        d.dispatch #= dispatch; sleep(1)
        val expected = if ((dispatch & 1) == 0) 1 else if ((dispatch & 2) == 0) 2
          else if ((dispatch & 4) == 0) 4 else 0
        assert((d.dispatchEvents.toInt & 31) == expected)
        assert(((d.dispatchEvents.toInt & 32) != 0) == ((dispatch & 7) == 1))
      }
    }
  }
}
