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

/** Directed test of the LS-EU two-access load sequencing + cross-line merge (Task 3).
  *
  * Preloads two adjacent 16-byte lines in behavioral memory, issues a misaligned
  * load spanning the boundary, and checks the merged value in the PRF. Verifies
  * the aligned fast path is unchanged (single access). */
class LsEuCrossSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc   // LS EU now writes NZVC for MOVE-to-memory
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  def simConfig = M68kSim().withVerilator
  def memByte(addr: Long): Int = ((addr * 7 + 0x11) & 0xff).toInt

  /** Big-endian value of `size` bytes starting at `base`. */
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg
    dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iStkPush #= false   // MUST default: an undriven stkPush makes a load PREDECREMENT,
                          // writing (base - size) to the dst instead of the loaded data.
    cd.waitSampling(80)
    (cd, mem)
  }

  def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
                size: SpinalEnumElement[Size.type], pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdst #= pdst; s.iPdstValid #= true; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 80): Boolean = {
    var saw = false; var n = 0
    while (!saw && n < maxCycles) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) saw = true
      cd.waitSampling(); n += 1
    }
    saw
  }

  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  test("aligned long load unchanged (fast path, single access)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x1000L
      preload(mem, base, 16)
      seed(dut, cd, preg = 10, value = base)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "aligned load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(base, 4),
        s"aligned ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(base, 4).toString(16)}")
    }
  }

  test("misaligned long load crossing a 16-byte line (offset 14)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val lineA = 0x2000L
      preload(mem, lineA, 32)         // both lines 0x2000 and 0x2010
      val addr = lineA + 14           // long spans 0x200E..0x2011 (lines 0x2000 / 0x2010)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 21, robId = 6)
      assert(waitCompletion(dut, cd, robId = 6), "cross-line load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 4),
        s"cross-line ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 4).toString(16)}")
    }
  }

  test("page-crossing word load (identity xlate, two accesses)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x2FFFL              // word spans 0x2FFF..0x3000 (page boundary)
      preload(mem, 0x2FF0L, 16)
      preload(mem, 0x3000L, 16)
      seed(dut, cd, preg = 10, value = addr)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.WORD, pdst = 22, robId = 7)
      assert(waitCompletion(dut, cd, robId = 7), "page-cross load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expected(addr, 2),
        s"page-cross ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expected(addr, 2).toString(16)}")
    }
  }
}
