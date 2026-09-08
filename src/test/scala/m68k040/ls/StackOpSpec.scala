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
  // Task #233 root-cause fix: this directed harness predates the fast/precise
  // store split (task P2.2, commit a1ecb7a). Its `Dut` wires no
  // MmuControlPlugin, so `LsEuPlugin`'s `fastStore` is unconditionally False and
  // EVERY store here classifies `precise=True` (LsEuPlugin.scala's
  // `sq.io.alloc.payload.precise := !fastStore`). A precise store's ROB
  // completion comes ONLY from `StoreQueue`'s at-head drain
  // (`headPreciseReady`), which is gated on `robHeadValidIn`/`robHeadIn` — bare
  // (non-`in()`) pass-through signals on `LsEuPlugin` whose only driver, absent
  // a real IO-backed override, is their own idle default
  // (`robHeadValidIn := False`, see LsEuPlugin.scala ~line 246). `LsEuSourcePlugin`
  // never wires them, so the push's completion could structurally never fire —
  // not a hazard, a permanent hang (`no completion for rob=1`). Mirrors
  // `LsEuFastPreciseSpec`'s `TbPreciseDrainWirePlugin`: a minimal glue plugin
  // giving both signals a genuine `in()`-backed IO so the test can drive them.
  class TbPreciseDrainWirePlugin(eu: LsEuPlugin) extends FiberPlugin {
    val logic = during build new Area {
      val iRobHeadIn      = in UInt (6 bits)
      val iRobHeadValidIn = in Bool ()
      eu.robHeadIn      := iRobHeadIn
      eu.robHeadValidIn := iRobHeadValidIn
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val eu     = new LsEuPlugin
    val src    = new LsEuSourcePlugin
    val wire   = new TbPreciseDrainWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, xlate, dcache, eu, src, wire)) }
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
      dut.wire.logic.iRobHeadIn #= 0; dut.wire.logic.iRobHeadValidIn #= false
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

      // Park the ROB head on the push's own robId (1) BEFORE issuing it: a precise
      // store's completion is driven solely by StoreQueue's at-head drain
      // (`headPreciseReady`, gated on `robHeadValidIn`/`robHeadIn` matching the SQ
      // head's robId, `!committed(head)`) -- see the Task #233 note on `Dut` above.
      // This directed test's SQ never holds more than one resident entry at a time,
      // so a static robId=1 target (rather than dynamically tracking `sq.head`, as
      // LsEuFastPreciseSpec's background forks do) suffices.
      dut.wire.logic.iRobHeadIn #= 1; dut.wire.logic.iRobHeadValidIn #= true

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
      //
      // The push (rob 1) has retired in any real pipeline by now, so the ROB head is the
      // pop itself (rob 2) -- and that is load-bearing, not cosmetic: this DUT hosts no
      // CacheControlService, so the LS EU classifies the pop as cache-INHIBITED (CACR.DE=0
      // reset state), and since f5f9fe13 an inhibited load launches ONLY at the ROB head
      // (`p4LaunchOk` = `p4AtRobHead && !olderStore`). Leaving the head parked on rob 1
      // is a permanent hang (`no completion for rob=2`), the same shape task #233 fixed
      // for the push.
      dut.wire.logic.iRobHeadIn #= 2; dut.wire.logic.iRobHeadValidIn #= true
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
