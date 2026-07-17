package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** One-shot diagnostic (task #139): trace the commit-side ExceptionUnit FSM
  * (E_DRAIN/E_STORE/.../E_REDIR, curVec, redirectValid/Pc) around the exact
  * cycle a wild-PC divergence fires, to test the hypothesis that EVERY
  * sampled wild-PC divergence this session (seeds 9, 13, and finding #3's
  * original) shows the DUT's a7 at exactly oracle-minus-8 -- i.e. a spurious,
  * wrongly-shaped 2-word exception frame push + garbage vector fetch, NOT
  * decode-garbage from a mis-computed EA. Uses the SAVED, VERIFIED repro
  * repros/fuzz-seed13-wild-pc-a7minus8.s (FUZZ_PROBE_SEED=147926526).
  * NOT part of the regular suite -- a targeted debug tool.
  * See fuzz-campaign-divergence-2026-07-16 memory. */
class WildPcA7TraceSpec extends AnyFunSuite {
  test("trace: wild-PC / a7-minus-8 (task #139, seed=13 repro)", VerilatorTest) {
    val path = "/home/qwertyoruiop/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/repros/fuzz-seed13-wild-pc-a7minus8.s"
    val src  = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }

    val simSeed = 147926526
    val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
    scala.util.Random.setSeed(simSeed)
    compiled.doSim("wildpc", simSeed) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      var cycleCount = 0
      var rawCommits = 0
      var lastPc = -1L
      var lastExcActive = false
      var wildTriggerCycle = -1

      cd.onSamplings {
        cycleCount += 1
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            rawCommits += 1
            lastPc = c.pc.toLong & 0xffffffffL
          }
        }
        val excActive = dut.rob.logic.exc.active.toBoolean
        if (excActive != lastExcActive) {
          val vec = dut.rob.logic.exc.curVec.toInt
          val curPc = dut.rob.logic.exc.curPc.toLong & 0xffffffffL
          println(f"[wildpc] EXC-ACTIVE-EDGE cyc=$cycleCount active=$excActive curVec=$vec curPc=0x$curPc%08x rawCommits=$rawCommits lastPc=0x$lastPc%08x")
        }
        lastExcActive = excActive
        val redirV = dut.rob.logic.exc.redirectValid.toBoolean
        if (redirV) {
          val redirPc = dut.rob.logic.exc.redirectPc.toLong & 0xffffffffL
          val vec = dut.rob.logic.exc.curVec.toInt
          println(f"[wildpc] REDIRECT-VALID cyc=$cycleCount redirectPc=0x$redirPc%08x curVec=$vec rawCommits=$rawCommits")
        }
        // Once the PC clearly leaves the sane 0x408xxxxx program range, latch a
        // trigger cycle and keep printing FSM/rob state for a bounded window
        // after it, to see the exact sequence that produced it.
        if (wildTriggerCycle < 0 && lastPc > 0 && ((lastPc >>> 24) != 0x40L)) {
          wildTriggerCycle = cycleCount
          println(f"[wildpc] WILD-PC-DETECTED cyc=$cycleCount lastPc=0x$lastPc%08x rawCommits=$rawCommits")
        }
        if (wildTriggerCycle >= 0 && cycleCount <= wildTriggerCycle + 5) {
          val robH = dut.rob.logic.head.toInt
          val robT = dut.rob.logic.tail.toInt
          val doFlush = dut.rob.logic.doFlushReg.toBoolean
          val flushPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
          val excAct = dut.rob.logic.exc.active.toBoolean
          val stStep = dut.rob.logic.exc.stStep.toInt
          println(f"[wildpc]   cyc=$cycleCount%4d robHead=$robH robTail=$robT doFlush=$doFlush flushPc=0x$flushPc%08x excActive=$excAct stStep=$stStep rawCommits=$rawCommits")
        }
        // Also print a window BEFORE the wild PC ever appears, gated on nearing
        // the expected divergence (rawCommits 50..58 covers idx 52..56 from the
        // saved log, with slack for the trace's own commit-counting convention).
        if (rawCommits >= 50 && rawCommits <= 58 && wildTriggerCycle < 0) {
          val excAct = dut.rob.logic.exc.active.toBoolean
          val stStep = dut.rob.logic.exc.stStep.toInt
          val doFlush = dut.rob.logic.doFlushReg.toBoolean
          println(f"[wildpc] pre cyc=$cycleCount%4d rawCommits=$rawCommits lastPc=0x$lastPc%08x excActive=$excAct stStep=$stStep doFlush=$doFlush")
        }
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
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

      cd.waitSampling(2000)
      println(f"[wildpc] DONE rawCommits=$rawCommits lastPc=0x$lastPc%08x wildTriggerCycle=$wildTriggerCycle")
    }
  }
}
