package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** One-shot diagnostic (task #139): trace the IQ + DivEuPlugin (CPLX cluster,
  * owns CMP2/CHK2) around the CMP2.B hang repro to see whether the crack's
  * compare uop ever reaches issue, and whether its LS-produced T0/T1 operand
  * wakeup ever fires. See fuzz-campaign-divergence-2026-07-16 memory. */
class Cmp2HangTraceSpec extends AnyFunSuite {
  test("trace: CMP2.B hang (task #139, seed=62 repro)", VerilatorTest) {
    val path = "/home/qwertyoruiop/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/repros/fuzz-seed62-cmp2-hang.s"
    val src  = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }

    val simSeed = 1366259935
    val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
    scala.util.Random.setSeed(simSeed)
    compiled.doSim("cmp2hang", simSeed) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      var cycleCount = 0
      var rawCommits = 0
      var lastLine = ""
      cd.onSamplings {
        cycleCount += 1
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) rawCommits += 1
        }
        // Once we're near the expected hang point (idx=37..41), watch for CHANGES only
        // (plus every LS wakeup event, even if the summarized state string is unchanged).
        if (rawCommits >= 35) {
          val deV = dut.divEu.issuePort.valid.toBoolean
          val deR = dut.divEu.issuePort.ready.toBoolean
          val deBusy = dut.divEu.logic.busy.toBoolean
          val deS1V  = dut.divEu.logic.s1Valid.toBoolean
          val deRid  = dut.divEu.issuePort.payload.robId.toInt
          val deOp   = dut.divEu.issuePort.payload.uop.op.toEnum.toString
          val iqSlots = dut.iq.logic.slots.zipWithIndex.filter(_._1.sel.toBoolean).map { case (s, i) =>
            f"s$i(rid=${s.hot.robId.toInt},op=${s.hot.op.toEnum},lsWait=${s.lsWait.toBoolean},cplxWait=${s.cplxWait.toBoolean},pA=${s.hot.psrcA.toInt}(v=${s.hot.psrcAValid.toBoolean}),pB=${s.hot.psrcB.toInt}(v=${s.hot.psrcBValid.toBoolean}))"
          }.mkString(" ")
          val robH = dut.rob.logic.head.toInt
          val robT = dut.rob.logic.tail.toInt
          val lsBusyHex = dut.iq.logic.lsBusy.toBigInt.toString(16)
          val lsWakeV = dut.iq.lsWakeupPort.valid.toBoolean
          val lsWakeP = dut.iq.lsWakeupPort.payload.toInt
          val line = f"deIssueV=$deV deIssueR=$deR deBusy=$deBusy deS1V=$deS1V deRid=$deRid deOp=$deOp robHead=$robH robTail=$robT lsBusy=0x$lsBusyHex iqSlots=[$iqSlots]"
          if (line != lastLine || lsWakeV) {
            println(f"[cmp2hang] cyc=$cycleCount%4d rawCommits=$rawCommits lsWakeV=$lsWakeV lsWakeP=$lsWakeP $line")
            lastLine = line
          }
        }
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      cd.waitSampling(6300)
      println(f"[cmp2hang] DONE rawCommits=$rawCommits cyc=$cycleCount")
    }
  }
}
