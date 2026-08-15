package m68k040.rename

import m68k040.{M68kSim, VerilatorTest}
import m68k040.decode.{DecodedUop, DecOp}
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed rename-only tests for Task 2's FP data + FPCC rename infrastructure
  * (fpRat/fpFree, fpccRat/fpccFree). No decode path drives writesFp/writesFpcc yet
  * (Task 6 lands DecodedUop's FP fields) -- so this spec exercises:
  *
  *  1. The RenameStage-specific identity-seed wiring (Step 9) via the SAME hosted
  *     Dut harness RenameStageSpec.scala already establishes for this codebase
  *     (RenameStage is a FiberPlugin -- it cannot be instantiated bare the way an
  *     earlier draft of this spec assumed; it needs a PluginHost + a
  *     DecodeUopService provider, mirrored here from RenameStageSpec.Dut).
  *  2. The RAW/WAW-visibility + O(1)-rollback behavior of the RatTable shapes
  *     RenameStage.scala actually instantiates for fpRat/fpccRat (Step 3), tested
  *     STANDALONE (mirrors RatTableSpec.scala's established pattern) rather than
  *     through the hosted RenameStage -- there, fpRat.io.writes/fpccRat.io.writes
  *     are ALREADY continuously driven by RenameStage's own RTL (tied to the
  *     Task-6-not-landed-yet `fpStubWritesFp`/`fpStubWritesFpcc` stub constants,
  *     hard False), so external sim pokes on those ports would race a live RTL
  *     driver instead of cleanly exercising the hazard/rollback logic. Testing the
  *     exact same RatTable component at fpRat's/fpccRat's real parameters sidesteps
  *     that and still proves the genuine hardware (same RatTable.scala source).
  */
class RenameStageFpSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv)) }
  }

  def waitInit(dut: Dut, cd: ClockDomain): Unit = {
    var n = 0
    while (!dut.dsrc.logic.src.ready.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
    assert(dut.dsrc.logic.src.ready.toBoolean, "src.ready never asserted (init did not complete)")
  }

  test("FP data + FPCC RAT: identity-seed at boot (RenameStage Step 9 wiring)") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.cdrv.logic.flushIn #= false
      dut.cdrv.logic.cmd.foreach(_.valid #= false)
      cd.waitSampling()
      waitInit(dut, cd)

      for (arch <- 0 until 8) {
        val phys = dut.ren.logic.fpRat.committedPhys(arch).toInt
        assert(phys == arch, s"FP arch reg $arch should identity-map to phys $arch at boot, got $phys")
      }
      val fpccPhys = dut.ren.logic.fpccRat.committedPhys(0).toInt
      assert(fpccPhys == 0, s"FPCC should identity-map arch 0 -> phys 0 at boot, got $fpccPhys")
    }
  }

  // ── fpRat shape (physIdWidth=4, archDepth=8, writePorts=2, commitPorts=2,
  // readPorts=6 -- exactly RenameStage.scala's fpRat instantiation) ──────────────
  def mkFp = RatTable(physIdWidth = 4, archDepth = 8, writePorts = 2, commitPorts = 2, readPorts = 6)

  test("FP data RAT (fpRat shape): RAW -- speculative write visible to a same-arch read", VerilatorTest) {
    M68kSim().withVerilator.compile(mkFp).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false
      dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      // Speculatively write FP2 -> phys 9 via write port 0 (mirrors decUop(0)'s
      // FP dst-allocation write, Step 6).
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 2; dut.io.writes(0).data #= 9
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 2; sleep(1)
      assert(dut.io.reads(0).data.toInt == 9, "speculative write should be visible to a same-arch read")
    }
  }

  test("FP data RAT (fpRat shape): WAW -- two write ports same arch reg, later port wins", VerilatorTest) {
    M68kSim().withVerilator.compile(mkFp).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false
      dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      // Both slots target the SAME architectural FP dst (FP4): mirrors two macro-
      // ops in one decode group both writing FP4 -- the freelist gives them
      // distinct phys ids, but the RAT's final mapping must be the YOUNGER
      // (slot1/port1) allocation.
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 4; dut.io.writes(0).data #= 10
      dut.io.writes(1).valid #= true; dut.io.writes(1).addr #= 4; dut.io.writes(1).data #= 11
      dut.clockDomain.waitSampling(); dut.io.writes.foreach(_.valid #= false); dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 4; sleep(1)
      assert(dut.io.reads(0).data.toInt == 11, "later write port (slot1) must win the WAW race")
    }
  }

  test("FP data RAT (fpRat shape): commit + rollback restores committed mapping (O(1))", VerilatorTest) {
    M68kSim().withVerilator.compile(mkFp).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false
      dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      // Commit FP2 -> phys 2 (the identity mapping RenameStage's init would seed).
      dut.io.commits(0).valid #= true; dut.io.commits(0).addr #= 2; dut.io.commits(0).data #= 2
      dut.clockDomain.waitSampling(); dut.io.commits(0).valid #= false
      // Speculatively remap FP2 -> phys 9, without committing.
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 2; dut.io.writes(0).data #= 9
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 2; sleep(1)
      assert(dut.io.reads(0).data.toInt == 9, "speculative mapping before rollback")
      // Roll back (mirrors RenameStage's `fpRat.io.rollback := flush`, Step 8).
      dut.io.rollback #= true; dut.clockDomain.waitSampling(); dut.io.rollback #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 2; sleep(1)
      assert(dut.io.reads(0).data.toInt == 2, "after rollback, FP2 must read back its committed mapping, not the speculative one")
    }
  }

  // ── fpccRat shape (physIdWidth=4, archDepth=1, writePorts=2, commitPorts=2,
  // readPorts=2 -- exactly RenameStage.scala's fpccRat instantiation, identical to
  // the existing nzvcRat/xRat shape). ────────────────────────────────────────────
  def mkFpcc = RatTable(physIdWidth = 4, archDepth = 1, writePorts = 2, commitPorts = 2, readPorts = 2)

  test("FPCC RAT (fpccRat shape): lone write port 0 persists + rollback restores committed mapping", VerilatorTest) {
    M68kSim().withVerilator.compile(mkFpcc).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false
      dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.io.writes.foreach(_.addr #= 0); dut.io.commits.foreach(_.addr #= 0); dut.io.reads.foreach(_.addr #= 0)
      dut.clockDomain.waitSampling()
      // Commit identity (arch 0 -> phys 0), mirroring RenameStage's Step 9 seed.
      dut.io.commits(0).valid #= true; dut.io.commits(0).data #= 0
      dut.clockDomain.waitSampling(); dut.io.commits(0).valid #= false
      // Lone write port 0 (mirrors a single FPCC-writing macro-op renaming alone
      // in slot 0 -- the exact bug class RatTable's doc comment + RatTableSpec's
      // "1-entry RAT" regression test guards against).
      dut.io.writes(0).valid #= true; dut.io.writes(0).data #= 10
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false
      dut.clockDomain.waitSampling(2)
      dut.io.reads(0).addr #= 0; sleep(1)
      assert(dut.io.reads(0).data.toInt == 10, "lone slot-0 FPCC write must win the youngest mapping")
      dut.io.rollback #= true; dut.clockDomain.waitSampling(); dut.io.rollback #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 0; sleep(1)
      assert(dut.io.reads(0).data.toInt == 0, "after rollback, FPCC must read back its committed mapping")
    }
  }
}
