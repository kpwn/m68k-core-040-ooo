package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** RING-ENTRY ATOMICITY (2026-09-27).
  *
  * Board evidence (100 MHz, build_id 0xD01DBDC5, Dhrystone running): the REPL's
  * `last-branches` returned entries whose `pc` cannot produce the reported `next` --
  * e.g. `pc=0x027de060` (a `beq.s` whose only successors are 0x027de050 and
  * 0x027de062) reported with `next=0x027d30da`, which is the RETURN address reached by
  * the `rts` four branches later. One PC appeared with three different `next` values;
  * 24 of 51 distinct PCs appeared with both `type` values.
  *
  * THE MECHANISM. A 128-bit ring entry is exposed as four 32-bit registers and the
  * REPL reads them with four INDEPENDENT AXI transactions, milliseconds apart over
  * JTAG. The ring is 32 entries deep and the CPU keeps retiring branches the whole
  * time, so entry `idx` is rewritten every 32 branch retirements -- hundreds of times
  * between two consecutive word reads. Each word therefore comes from a DIFFERENT
  * event, and the four words are stitched into one plausible-looking line.
  *
  * That is not a hypothesis about pipeline skew: the RobPlugin holds every field of an
  * event in ONE `branchTrainMem` row written by ONE `branchCompletion`, and
  * `DebugCtrlPlugin` writes all 128 bits of a ring entry in one cycle. The skew is
  * introduced by the READ, and only by the read. The off-by-N in the board data
  * confirms it: entry 1 is off by three branch events, entry 2 by two -- no fixed
  * pipeline skew does that.
  *
  * THE PROPERTY, checked here: an entry read out of a 128-bit forensic ring is ONE
  * event. Every field of a driven event carries the same sequence number, so any entry
  * whose fields disagree about their sequence number is a defect instance. The reads
  * happen WHILE the event stream runs -- exactly the board posture, and exactly what
  * `DebugHistorySpec` never covers, because it stops the stimulus before reading.
  */
class DebugRingAtomicitySpec extends AnyFunSuite {
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val history = new DebugHistoryStubPlugin(2)
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 3, historyDepth = 32)
    db.on { host.asHostOf(Seq[FiberPlugin](history, dbg)) }
    def axi = dbg.logic.dbgAxi
  }

  /** Every field of branch event `i` is a function of `i`, so a read-back entry
    * self-identifies and a stitched entry cannot pass.
    *
    * `taken`/`mispredicted`/`branchType` are driven from seq bits 5..8, NOT 0..2. The
    * ring is 32 deep, so a tear swaps in an event 32 (or a multiple of 32) later: a meta
    * derived from the LOW bits would be bit-identical across that swap and the tear would
    * hide in the one field the board could still cross-check. That aliasing is not
    * hypothetical -- it is why the board's `taken`/`type`/`mispredict` looked mutually
    * consistent with a `next` that belonged to a different branch, and why the skew was
    * first read as "`pc` alone is wrong". */
  private def brPc(i: Long): Long   = 0x20000000L + (i & 0xffffff) * 4
  private def brNext(i: Long): Long = 0x30000000L + (i & 0xffffff) * 4
  private def brMeta(i: Long): Long = (((i >> 7) & 3) << 2) | (((i >> 6) & 1) << 1) | ((i >> 5) & 1)

  private def exVec(i: Long): Long  = i & 0xff
  private def exPc(i: Long): Long   = 0x40000000L + (i & 0xffffff) * 4
  private def exFa(i: Long): Long   = 0x50000000L + (i & 0xffffff) * 4
  private def exHnd(i: Long): Long  = 0x60000000L + (i & 0xffffff) * 4

  test("a branch-ring entry read while the CPU keeps retiring branches is ONE event") {
    M68kSim().compile(new Dut).doSim(seed = 42) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid.foreach(_ #= false)
      dut.history.logic.branchValid #= false
      dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)

      // The board posture: a branch retires every cycle, forever. The 32-entry ring
      // turns over every 32 cycles while the debug reads are in flight.
      var seq = 0L
      val driver = fork {
        while (true) {
          dut.history.logic.branchValid #= true
          dut.history.logic.branchPc #= brPc(seq)
          dut.history.logic.branchNextPc #= brNext(seq)
          dut.history.logic.branchTaken #= ((seq >> 5) & 1) != 0
          dut.history.logic.branchMispredicted #= ((seq >> 6) & 1) != 0
          dut.history.logic.branchType #= ((seq >> 7) & 3).toInt
          cd.waitSampling()
          seq += 1
        }
      }
      cd.waitSampling(64) // let the ring fill and wrap at least twice

      // The PREMISE of this test is that the ring really is being overwritten under the
      // reads. Assert it, so the test can never degenerate into "the stream was idle" and
      // pass a design that tears.
      val headBefore = seq
      val torn = scala.collection.mutable.ArrayBuffer[String]()
      for (index <- 0 until 32) {
        val base = DebugRegMap.OFF_BRANCH_RING_BODY + index * 16
        val pc   = DbgAxiDriver.read(dut.axi, cd, base)
        val next = DbgAxiDriver.read(dut.axi, cd, base + 4)
        val meta = DbgAxiDriver.read(dut.axi, cd, base + 8)
        val i    = (pc - 0x20000000L) / 4
        if (pc != brPc(i) || next != brNext(i) || meta != brMeta(i))
          torn += f"branch[$index%2d] pc=0x$pc%08x (seq=$i) next=0x$next%08x " +
            f"(expected 0x${brNext(i)}%08x) meta=0x$meta%x (expected 0x${brMeta(i)}%x)"
      }
      val turnovers = (seq - headBefore) / 32
      driver.terminate()
      dut.history.logic.branchValid #= false
      assert(turnovers >= 2,
        s"test premise lost: the ring turned over only $turnovers times during the read " +
          "sweep, so an atomicity defect would not be exercised")
      assert(torn.isEmpty,
        s"${torn.length} of 32 branch-ring entries were stitched from more than one " +
          s"event:\n${torn.mkString("\n")}")
    }
  }

  test("an exception-ring entry read while exceptions keep arriving is ONE event") {
    M68kSim().compile(new Dut).doSim(seed = 43) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.history.logic.pcValid.foreach(_ #= false)
      dut.history.logic.branchValid #= false
      dut.history.logic.exceptionValid #= false
      cd.waitSampling(20)

      var seq = 0L
      val driver = fork {
        while (true) {
          dut.history.logic.exceptionValid #= true
          dut.history.logic.exceptionVector #= exVec(seq).toInt
          dut.history.logic.exceptionPc #= exPc(seq)
          dut.history.logic.faultAddress #= exFa(seq)
          dut.history.logic.handlerPc #= exHnd(seq)
          cd.waitSampling()
          seq += 1
        }
      }
      cd.waitSampling(64)

      val headBefore = seq
      val torn = scala.collection.mutable.ArrayBuffer[String]()
      for (index <- 0 until 32) {
        val base = DebugRegMap.OFF_EXC_RING_BODY + index * 16
        val vec  = DbgAxiDriver.read(dut.axi, cd, base)
        val pc   = DbgAxiDriver.read(dut.axi, cd, base + 4)
        val fa   = DbgAxiDriver.read(dut.axi, cd, base + 8)
        val hnd  = DbgAxiDriver.read(dut.axi, cd, base + 12)
        val i    = (pc - 0x40000000L) / 4
        if (vec != exVec(i) || fa != exFa(i) || hnd != exHnd(i))
          torn += f"exc[$index%2d] pc=0x$pc%08x (seq=$i) vec=0x$vec%02x " +
            f"(expected 0x${exVec(i)}%02x) fa=0x$fa%08x (expected 0x${exFa(i)}%08x) " +
            f"handler=0x$hnd%08x (expected 0x${exHnd(i)}%08x)"
      }
      val turnovers = (seq - headBefore) / 32
      driver.terminate()
      dut.history.logic.exceptionValid #= false
      assert(turnovers >= 2,
        s"test premise lost: the ring turned over only $turnovers times during the read " +
          "sweep, so an atomicity defect would not be exercised")
      assert(torn.isEmpty,
        s"${torn.length} of 32 exception-ring entries were stitched from more than one " +
          s"event:\n${torn.mkString("\n")}")
    }
  }
}
