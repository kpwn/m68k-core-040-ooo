package m68k040.exception

import m68k040.{M68kSim, VerilatorTest}
import m68k040.fuzz.{FuzzCoreDut, FuzzDut}
import m68k040.ls.BehavioralMemAgent
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Full-path regression for the ExceptionUnit -> LsEu -> StoreQueue -> D-cache
  * arbitration boundary. Fast COPYBACK stores may have retired while their SQ
  * entries are still draining. Exception entry must leave that drain Stream live
  * throughout E_DRAIN, then take the port only for a real held frame command. */
class ExceptionStoreDrainArbSpec extends AnyFunSuite {
  private val loadAddr = ProgramAssembler.DefaultLoadAddress
  private val handler  = loadAddr + 0x100L
  private val storeBase = 0x00030000L

  private val src = {
    val stores = (0 until 8).map { i =>
      f"move.l #0x${0x11000000L + i}%08x,0x${storeBase + i * 0x1000L}%08x"
    }
    (Seq(
      ".text", ".org 0", "_start:",
      "lea 0x00020000,%a7") ++
      stores ++ Seq(
      ".word 0x4afc",               // ILLEGAL -> vector 4
      "bra .",                      // must be flushed before execution
      ".org 0x100", "_handler:",
      "bra _handler")).mkString("\n")
  }

  test("exception E_DRAIN lets older committed SQ stores finish before frame stores",
       VerilatorTest) {
    val image = ProgramAssembler.assemble(src, loadAddr).getOrElse(fail("assemble failed"))
    M68kSim().withVerilator.compile(new FuzzCoreDut).doSim("excStoreDrain", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      new BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)

      // Physical vector 4 contains the handler PC in big-endian byte order.
      for (i <- 0 until 4)
        dmem.pokeByte(4 * 4L + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)

      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.ctrl.logic.itt0 #= 0; dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= 0; dut.ctrl.logic.dtt1 #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= true
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false
      dut.wire.logic.seedAddr #= 0
      dut.wire.logic.seedData #= 0

      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling()
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Match-all transparent translations: WT instructions, COPYBACK data.
      // Program these after reset; doing it beside initial inputs is a vacuous
      // harness bug because forkStimulus resets the registers afterward.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.itt0 #= 0x00FFC000L
      dut.ctrl.logic.dtt0 #= 0x00FFC020L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L // DE|IE
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= 0x00100000L
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      var sawExcWithOlderSq = false
      var sawDrainFireDuringExc = false
      var drainFires = 0
      var storeMisses = 0
      var olderAcksBeforeFrame = 0
      var frameFires = 0
      var frameStarted = false
      var redirected = false
      var cycles = 0

      while (!redirected && cycles < 5000) {
        cd.waitSampling()
        val excActive = dut.rob.logic.excActive.toBoolean
        val sqEmpty = dut.lsEu.logic.sq.io.empty.toBoolean
        val drainFire = dut.lsEu.logic.sq.io.drain.valid.toBoolean &&
          dut.lsEu.logic.sq.io.drain.ready.toBoolean
        val frameFire = dut.rob.logic.exc.dcStore.valid.toBoolean &&
          dut.rob.logic.exc.dcStore.ready.toBoolean

        if (excActive && !sqEmpty) sawExcWithOlderSq = true
        if (drainFire) {
          drainFires += 1
          if (excActive && !frameStarted) sawDrainFireDuringExc = true
        }
        if (dut.dcache.logic.storeMissDiscovered.toBoolean) storeMisses += 1
        if (dut.dcache.logic.storeAckReg.toBoolean && !frameStarted)
          olderAcksBeforeFrame += 1
        if (frameFire) {
          if (!frameStarted) {
            assert(sqEmpty,
              "ExceptionUnit stole the cache store port before older SQ work drained")
            frameStarted = true
          }
          frameFires += 1
        }
        if (dut.rob.logic.doFlushReg.toBoolean &&
            ((dut.rob.logic.flushPcReg.toLong & 0xffffffffL) == handler))
          redirected = true
        cycles += 1
      }

      assert(redirected, "exception entry did not reach the vector-4 handler")
      assert(sawExcWithOlderSq,
        "test never entered E_DRAIN with an older committed SQ entry resident")
      assert(sawDrainFireDuringExc,
        "SQ drain did not handshake while excActive; an excActive-wide ready block would deadlock")
      assert(drainFires == 8, s"expected eight older store drain fires, saw $drainFires")
      assert(storeMisses == 8,
        s"all eight cold COPYBACK stores must exercise the slow drain barrier, saw $storeMisses misses")
      assert(olderAcksBeforeFrame == 8,
        s"frame started before all older stores terminated: acks=$olderAcksBeforeFrame")
      assert(frameFires == 4,
        s"format-$$0 exception entry must accept four frame words, saw $frameFires")
    }
  }
}
