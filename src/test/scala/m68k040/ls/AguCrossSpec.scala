package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed test of the AGU cross-line / cross-page detection (Task 1).
  *
  * Drives an LS µop into the EU and, once it has registered into S1, samples the
  * simPublic cross-detection signals (`s1CrossLine`, `s1CrossPage`, `s1TwoAccess`,
  * `s1AddrB`). The fast path (aligned) must report `twoAccess=false`. */
class AguCrossSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc   // LS EU now writes NZVC for MOVE-to-memory
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def simConfig = M68kSim().withVerilator

  def initDut(dut: Dut): ClockDomain = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> LEA path
    s.iStkPush #= false   // an undriven stkPush would predecrement the probe load's EA
    cd.waitSampling(80)
    cd
  }

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg
    dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  /** Issue a load and, the cycle it registers into S1, sample the cross signals.
    * Returns (crossLine, crossPage, twoAccess, addrB). */
  def probeLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
                size: SpinalEnumElement[Size.type]): (Boolean, Boolean, Boolean, Long) = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdst #= 20; s.iPdstValid #= true; s.iRobId #= 1
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
    // S1 is now latched (issue fired this sampling). Sample the registered signals.
    cd.waitSampling()
    val cl = dut.eu.logic.s1CrossLine.toBoolean
    val cp = dut.eu.logic.s1CrossPage.toBoolean
    val ta = dut.eu.logic.s1TwoAccess.toBoolean
    val ab = dut.eu.logic.s1AddrB.toLong & 0xffffffffL
    // let it finish so the EU returns to idle for the next probe
    cd.waitSampling(30)
    (cl, cp, ta, ab)
  }

  test("aligned long at offset 0 -> twoAccess false", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x1000L)
      val (cl, cp, ta, _) = probeLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG)
      assert(!cl, "aligned long: no line cross")
      assert(!cp, "aligned long: no page cross")
      assert(!ta, "aligned long: twoAccess false (fast path)")
    }
  }

  test("long at line offset 14 -> crossLine + twoAccess, addrB = next line", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x100EL)   // offset 14 within line 0x1000
      val (cl, cp, ta, ab) = probeLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG)
      assert(cl, "offset 14 + 4 bytes spans the 16-byte line boundary")
      assert(!cp, "still within the same page")
      assert(ta, "twoAccess set")
      assert(ab == 0x1010L, s"addrB must be next line base 0x1010, got 0x${ab.toHexString}")
    }
  }

  test("word at page offset 0xFFF -> crossPage + twoAccess, addrB = next page", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x1FFFL)   // page offset 0xFFF
      val (cl, cp, ta, ab) = probeLoad(dut, cd, basePreg = 10, disp = 0, Size.WORD)
      assert(cl, "0xFFF + 2 also crosses the line boundary")
      assert(cp, "0xFFF + 2 crosses the 4 KB page boundary")
      assert(ta, "twoAccess set")
      assert(ab == 0x2000L, s"addrB must be next page base 0x2000, got 0x${ab.toHexString}")
    }
  }

  test("byte access never crosses", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x1FFFL)   // last byte of the page
      val (cl, cp, ta, _) = probeLoad(dut, cd, basePreg = 10, disp = 0, Size.BYTE)
      assert(!cl, "byte: no line cross")
      assert(!cp, "byte: no page cross")
      assert(!ta, "byte: twoAccess false")
    }
  }
}
