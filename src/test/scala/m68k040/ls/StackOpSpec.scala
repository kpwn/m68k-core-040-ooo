package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.M68kParams
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

/** Directed validation of the STACK-PUSH store primitive (BSR/JSR push mechanism):
  *   - addr = base(A7) - sizeBytes (predecrement),
  *   - store DATA = imm (retPC), NOT a register,
  *   - int dst = the predecremented address (the new A7) — the store's lone int write.
  * Then a plain disp-addressed LOAD pops the pushed value back (the RTS/RTR pop reads
  * (A7) without a side-effect; A7 is bumped by the trailing ibranch in Tasks 4/5).
  * Verifies A7 write-back + stack memory + the popped value round-trip. */
class StackOpSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src)) }
  }

  test("stack push.l (-(A7), data=imm, A7 write) then pop.l ((A7) load) round trip", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val s = dut.src.logic
      s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false; s.iStkPush #= false
      s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> LEA path
      s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
      s.iPsrcA #= 0; s.iPsrcB #= 0; s.iImm #= 0; s.iPdst #= 0; s.iPdstValid #= false; s.iRobId #= 0
      cd.waitSampling(80)

      def seed(preg: Int, value: Long): Unit = {
        s.seedValid #= true; s.seedAddr #= preg; s.seedData #= BigInt(value & 0xffffffffL)
        cd.waitSampling(); s.seedValid #= false; cd.waitSampling(2)
      }
      def waitComp(rob: Int): Unit = {
        var saw = false
        for (_ <- 0 until 60 if !saw) { if (s.cValid.toBoolean && s.cRob.toInt == rob) saw = true; cd.waitSampling() }
        assert(saw, s"no completion for rob=$rob")
      }
      def obs(preg: Int): Long = { s.obsIntAddr #= preg; cd.waitSampling(); s.obsIntData.toLong & 0xffffffffL }

      // A7 (phys reg 15, identity) = 0x40802000; retPC = 0x40800042.
      val a7Phys = 15
      val a7Init = 0x40802000L
      val retPc  = 0x40800042L
      seed(a7Phys, a7Init)

      // PUSH.L: stkPush store. base = A7 (phys 15), imm = retPC, int dst = phys 20 (the
      // new A7 value), size LONG. addr = A7 - 4; mem[A7-4] = retPC; phys20 := A7-4.
      s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
      s.iStkPush #= true
      s.iPsrcA #= a7Phys; s.iPsrcAValid #= true
      s.iPsrcB #= 0; s.iPsrcBValid #= false
      s.iImm #= BigInt(retPc)
      s.iPdst #= 20; s.iPdstValid #= true; s.iRobId #= 1
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false; s.iStkPush #= false
      waitComp(1)

      val newA7 = obs(20)
      assert(newA7 == ((a7Init - 4) & 0xffffffffL),
        f"push: new A7 (phys20)=0x$newA7%x expected 0x${(a7Init - 4) & 0xffffffffL}%x")

      // Commit + drain the store so memory holds retPC.
      s.iSqCommitValid #= true; s.iSqCommitRob #= 1; cd.waitSampling(); s.iSqCommitValid #= false
      cd.waitSampling(40)
      val stk = (0 until 4).foldLeft(0L)((a, i) => (a << 8) | (mem.peekByte((a7Init - 4 + i)) & 0xffL))
      assert(stk == retPc, f"push: mem[A7-4]=0x$stk%x expected retPc=0x$retPc%x")

      // POP.L: plain load from (new A7) -> phys 21. (A7)+ postinc is folded into the
      // ibranch in Tasks 4/5; here we just validate the pop READ reads the pushed value.
      s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
      s.iStkPush #= false
      s.iPsrcA #= 20; s.iPsrcAValid #= true      // base = new A7 (phys20 = A7-4)
      s.iPsrcBValid #= false
      s.iImm #= 0
      s.iPdst #= 21; s.iPdstValid #= true; s.iRobId #= 2
      cd.waitSamplingWhere(s.iReady.toBoolean)
      s.iValid #= false
      waitComp(2)
      val popped = obs(21)
      assert(popped == retPc, f"pop: loaded 0x$popped%x expected retPc=0x$retPc%x")
    }
  }
}
