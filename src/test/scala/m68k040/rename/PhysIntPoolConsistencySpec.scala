package m68k040.rename

import m68k040.M68kSim
import m68k040.decode.DecOp
import m68k040.execute.regfile.RegfileSpec
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** The int physical-register POOL SIZE must be ONE number, agreed by all three places
  * that encode it:
  *
  *   1. `RenameStage.logic.intFree` — the allocator that HANDS OUT ids.
  *   2. `Global.PHYS_INT_REGS` (= `M68kParams.physInt`) — the width of every int busy
  *      bitmap in `IssueQueuePlugin` (`sbInt.busy`, `sbIntClr`, `lsBusy`, `cplxBusy`,
  *      `aluSlowIntBusy`) and the depth of `sbInt.physToSlot`.
  *   3. `RegfileSpec.Int.depth` — the depth of the int PRF's backing `Mem`.
  *
  * When (1) exceeds (2)/(3) the allocator hands out ids that DO NOT EXIST downstream:
  *   - the IQ busy/wakeup bitmaps have no bit for them. `lsBusy(p) := True` and `lsBusy(p)`
  *     are dynamic index forms on a `Bits(N bits)` register, so past the end the SET is
  *     dropped and the READ is 0. A producer on such an id never marks itself busy, and
  *     its consumers are declared ready before it has written;
  *   - the PRF `Mem` write for that id is out of range too, so the producing write never
  *     lands and the dependent read returns whatever the row held.
  *
  * The result is a WRONG OPERAND with no fault raised anywhere. In an early-ROM poll loop
  * that presents as a hang: PC pinned, exc_count = 0.
  *
  * This is a REGRESSION test, not a new invariant. The same class already shipped twice:
  * once when the PRF was left at 48 after T0/T1 widened the pool to 50 (see the comment
  * on `RegfileSpec.Int`), and once when `intFree`'s `physCount = 54` was ported onto a
  * tree whose `M68kParams.physInt` is 50 — which booted to a frozen PC with exc_count=0
  * on silicon while every simulation stayed green.
  *
  * NOTE the reachability the dynamic test measures. The freelist init fills the ring with
  * `archCount .. physCount-1` IN ORDER and pops from the head, so the FIRST ~38 int
  * destinations renamed after reset walk the WHOLE pool including its top. An over-sized
  * pool is therefore not a "heavy register pressure" corner — it is reached by the first
  * few dozen instructions of any program.
  */
class PhysIntPoolConsistencySpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv)) }
  }

  private val physIntParam = m68k040.M68kParams().physInt
  private val prfDepth     = RegfileSpec.Int.depth

  /** `intFree`'s physCount is a plain constructor literal in RenameStage; read it back off
    * an elaborated component rather than restating it here (a second hardcode would be the
    * very drift this test exists to catch). Elaboration only — no Verilator build. */
  private def elaboratedFreelistPhysCount(): Int = {
    var d: Dut = null
    SpinalConfig(targetDirectory = "simWorkspace/physIntPoolCheck").generateVerilog { d = new Dut; d }
    // Read AFTER elaboration returns: `logic` is a `during build` Area, so touching it
    // from inside the generate block parks the fiber engine.
    d.ren.logic.intFree.physCount
  }

  test("int phys pool: freelist size, IQ busy-bitmap width and PRF Mem depth agree") {
    val freelistPhysCount = elaboratedFreelistPhysCount()
    assert(freelistPhysCount == physIntParam,
      s"rename int freelist physCount=$freelistPhysCount but M68kParams.physInt=" +
      s"$physIntParam (= Global.PHYS_INT_REGS, the width of IssueQueuePlugin's sbInt.busy " +
      s"/ sbIntClr / lsBusy / cplxBusy / aluSlowIntBusy and the depth of sbInt.physToSlot). " +
      s"ids $physIntParam..${freelistPhysCount - 1} index PAST every int busy bitmap: the " +
      s"set and the read are both dropped, and the read's value is a synthesis don't-care.")
    assert(freelistPhysCount == prfDepth,
      s"rename int freelist physCount=$freelistPhysCount but RegfileSpec.Int.depth=" +
      s"$prfDepth (the int PRF's backing Mem): ids $prfDepth..${freelistPhysCount - 1} " +
      s"address past the Mem, so the producing write never lands.")
  }

  test("int phys pool: every pdst rename hands out exists in the PRF and the IQ bitmaps") {
    M68kSim().compile(new Dut).doSim { dut =>
      val freelistPhysCount = dut.ren.logic.intFree.physCount
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.dsrc.logic.s1v       #= false
      dut.cdrv.logic.flushIn   #= false
      dut.cdrv.logic.cmd.foreach(_.valid #= false)
      cd.waitSampling()

      var n = 0
      while (!dut.dsrc.logic.src.ready.toBoolean && n < 400) { cd.waitSampling(); n += 1 }
      assert(dut.dsrc.logic.src.ready.toBoolean, "freelist init never completed")

      // Never commit: nothing is pushed back, so the ring is popped straight through
      // archCount..physCount-1 and the TOP of the pool is reached deterministically.
      val u      = dut.dsrc.logic.src.payload(0)
      val seen   = scala.collection.mutable.ArrayBuffer[Int]()
      var cycles = 0
      var stalled = 0            // consecutive cycles with the source held off
      while (stalled < 40 && cycles < 4000) {
        u.valid #= true; u.pc #= 0; u.nextPc #= 0
        u.op #= DecOp.MOVE; u.cluster #= Cluster.INT; u.size #= Size.LONG
        u.srcAReg #= 0; u.srcAValid #= false
        u.srcBReg #= 0; u.srcBValid #= false
        u.dstReg  #= (seen.size % 8); u.dstValid #= true
        u.useImm #= false; u.imm #= 0
        u.readsNzvc #= false; u.readsX #= false
        u.writesNzvc #= false; u.writesX #= false
        u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
        u.unimplemented #= false
        // Every OTHER rename-class write bit must be driven explicitly: SpinalSim leaves
        // un-poked top-level inputs at a random value, and a stray writesFp/writesFpcc/
        // writesNzvc would drain that (much smaller) pool first and stop the run before
        // the int ring is walked — which is exactly what made an earlier draft of this
        // test report 7 / 14 / 33 renames on three successive runs.
        u.usesFpSrcA #= false; u.fpSrcAReg #= 0
        u.usesFpSrcB #= false; u.fpSrcBReg #= 0
        u.writesFp   #= false; u.fpDstReg  #= 0
        u.readsFpcc  #= false; u.writesFpcc #= false
        u.faulted #= false
        dut.dsrc.logic.src.valid #= true
        val accepted = dut.dsrc.logic.src.ready.toBoolean
        cd.waitSampling(); cycles += 1
        if (dut.sink.logic.out.valid.toBoolean && dut.sink.logic.out.ready.toBoolean) {
          seen += dut.sink.logic.out.payload(0).pdst.toInt
        }
        // Nothing is ever committed, so once the pool is drained the source stays held
        // off forever. A short run of held cycles means the whole ring has been walked.
        if (accepted) stalled = 0 else stalled += 1
      }
      dut.dsrc.logic.src.valid #= false

      assert(seen.size >= freelistPhysCount - m68k040.isa.Isa.ARCH_INT_REGS - 2,
        s"only ${seen.size} destinations renamed before the pool stalled; the test did not " +
        s"walk the ring (expected ~${freelistPhysCount - m68k040.isa.Isa.ARCH_INT_REGS})")
      val bad = seen.filter(id => id >= physIntParam || id >= prfDepth).distinct.sorted
      assert(bad.isEmpty,
        s"rename allocated int physical register id(s) ${bad.mkString(",")} after only " +
        s"${seen.size} renamed destinations, but the int PRF Mem has $prfDepth entries and " +
        s"every IssueQueuePlugin int busy bitmap is $physIntParam bits wide. Those ids have " +
        s"no storage and no busy bit: the PRF write is out of range, and the busy set is " +
        s"dropped while the busy read is 0, so consumers issue before the producer has " +
        s"written. Allocation order: ${seen.mkString(",")}")
    }
  }
}
