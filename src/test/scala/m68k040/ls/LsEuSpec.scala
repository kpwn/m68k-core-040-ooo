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

class LsEuSpec extends AnyFunSuite {
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
  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def expectedLong(base: Long): BigInt =
    (0 until 4).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg; dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false
    s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> every load
                          // takes the LEA address-generate path (dst = EA, not the data).
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    cd.waitSampling(80) // PRF init sweep
    (cd, mem)
  }

  def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, size: SpinalEnumElement[Size.type], pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdst #= pdst; s.iPdstValid #= true; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  def issueStore(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, dataPreg: Int, size: SpinalEnumElement[Size.type], robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= dataPreg; s.iPsrcBValid #= true
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 60): Boolean = {
    var saw = false
    var n = 0
    while (!saw && n < maxCycles) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) saw = true
      cd.waitSampling(); n += 1
    }
    saw
  }

  test("load miss refills then writes PRF + fires completion", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x1000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)   // base preg = vaddr base
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "load completion must fire")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base), s"load result ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expectedLong(base).toString(16)}")
    }
  }

  test("load hit (second load same line) returns correct data", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x2000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 1)
      assert(waitCompletion(dut, cd, robId = 1), "first load")
      cd.waitSampling(4)
      // second load in same line (disp +8), should hit
      issueLoad(dut, cd, basePreg = 10, disp = 8, Size.LONG, pdst = 21, robId = 2)
      assert(waitCompletion(dut, cd, robId = 2), "second (hit) load")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base + 8), s"hit result ${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }

  test("store then commit drains to memory", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x3000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xDEADBEEFL)  // store data preg
      issueStore(dut, cd, basePreg = 10, disp = 4, dataPreg = 11, Size.LONG, robId = 7)
      assert(waitCompletion(dut, cd, robId = 7), "store completion (SQ alloc)")
      cd.waitSampling(2)
      // not committed yet -> memory unchanged
      assert(mem.peekByte(base + 4) != 0xDE, "store must not drain before commit")
      // commit the store
      dut.src.logic.iSqCommitValid #= true; dut.src.logic.iSqCommitRob #= 7
      cd.waitSampling()
      dut.src.logic.iSqCommitValid #= false
      cd.waitSampling(12)
      assert(mem.peekByte(base + 4) == 0xDE, "committed store drains to memory")
      assert(mem.peekByte(base + 7) == 0xEF, "store byte +7")
    }
  }

  test("store-to-load forward (uncommitted store)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xABCD1234L)
      // store (robId 3) then younger load (robId 5) same addr -> forward
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitCompletion(dut, cd, robId = 3), "store alloc")
      cd.waitSampling(2)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 22, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "forwarded load")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0xABCD1234L), s"forwarded ${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }

  test("younger load that misses L1D forwards a committed store through the drain window", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      // base is a line NEVER loaded into L1D -> a load here MISSES. The store also
      // misses (write-through, no-allocate) so it does NOT update the L1D line.
      val base = 0x6000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))  // STALE memory
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0x0BADF00DL)
      // store (robId 3) then COMMIT it -> it begins draining to memory.
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitCompletion(dut, cd, robId = 3), "store alloc")
      dut.src.logic.iSqCommitValid #= true; dut.src.logic.iSqCommitRob #= 3
      cd.waitSampling()
      dut.src.logic.iSqCommitValid #= false
      // Immediately issue a YOUNGER load (robId 5) to the same addr. Even though the
      // line misses L1D and the store may already be mid-drain, the SQ entry stays
      // resident until its memory write is ACKed -> the load must FORWARD the store
      // data, not refill stale memory.
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 23, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "younger load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 23; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0x0BADF00DL),
        s"load must forward store data, got ${dut.src.logic.obsIntData.toBigInt.toString(16)} (stale = ${expectedLong(base).toString(16)})")
    }
  }

  // DIRECTED REPRO of the cross-line store-after-load drain bug. A cross-line LONG
  // store (slot A low line / slot B high line), AFTER a cross-line LOAD to the same
  // address, must write BOTH slot A and slot B through to backing memory.
  def commit(dut: Dut, cd: ClockDomain, robId: Int): Unit = {
    dut.src.logic.iSqCommitValid #= true; dut.src.logic.iSqCommitRob #= robId
    cd.waitSampling()
    dut.src.logic.iSqCommitValid #= false
  }
  test("cross-line store after cross-line load drains BOTH slots", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x3FFEL              // long spans 0x3FFE..0x4001 (lines 0x3FF0 / 0x4000)
      // seed both lines so the load refills clean lines
      for (i <- 0 until 16) { mem.pokeByte(0x3FF0L + i, memByte(0x3FF0L + i)); mem.pokeByte(0x4000L + i, memByte(0x4000L + i)) }
      seed(dut, cd, preg = 10, value = addr)
      // initial cross-line store (the seed) of 0x12345678
      seed(dut, cd, preg = 11, value = 0x12345678L)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 1)
      assert(waitCompletion(dut, cd, robId = 1), "seed store alloc")
      commit(dut, cd, robId = 1)
      cd.waitSampling(20)
      assert(mem.peekByte(0x3FFEL) == 0x12, s"seed slotA drained: ${mem.peekByte(0x3FFEL).toHexString}")
      assert(mem.peekByte(0x4000L) == 0x56, s"seed slotB drained: ${mem.peekByte(0x4000L).toHexString}")
      // cross-line LOAD to the same address
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 2)
      assert(waitCompletion(dut, cd, robId = 2), "cross-line load")
      cd.waitSampling(4)
      // cross-line STORE of 0xCAFEBABE to the same address
      seed(dut, cd, preg = 12, value = 0xCAFEBABEL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 12, Size.LONG, robId = 3)
      assert(waitCompletion(dut, cd, robId = 3), "cross-line store alloc")
      commit(dut, cd, robId = 3)
      cd.waitSampling(30)
      assert(mem.peekByte(0x3FFEL) == 0xCA, s"slotA write-through dropped: dut=0x${mem.peekByte(0x3FFEL).toHexString} exp=0xca")
      assert(mem.peekByte(0x3FFFL) == 0xFE, s"slotA byte1: dut=0x${mem.peekByte(0x3FFFL).toHexString} exp=0xfe")
      assert(mem.peekByte(0x4000L) == 0xBA, s"slotB byte0: dut=0x${mem.peekByte(0x4000L).toHexString} exp=0xba")
      assert(mem.peekByte(0x4001L) == 0xBE, s"slotB byte1: dut=0x${mem.peekByte(0x4001L).toHexString} exp=0xbe")
    }
  }

  test("flush squashes an uncommitted store (never drains)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0x55667788L)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 9)
      assert(waitCompletion(dut, cd, robId = 9), "store alloc")
      cd.waitSampling(2)
      // flush before commit -> squashed, never drains
      dut.src.logic.iSqFlush #= true
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      cd.waitSampling(12)
      assert(mem.peekByte(base + 0) == memByte(base + 0), "squashed store must not drain")
    }
  }
}
