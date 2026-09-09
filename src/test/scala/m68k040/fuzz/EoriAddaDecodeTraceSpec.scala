package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** One-shot diagnostic (task #140): trace decode-stage pushes (fed/pushReg,
  * already-tapped simPublic fields) around the minimal EORI.L-mem-then-ADDA.L
  * repro to find exactly which pushed uop gets faulted=true faultVector=4, and
  * what the microcode engine / predecode classification looked like when it
  * happened. See eori-mem-then-adda-spurious-illegal-2026-07-16 memory. */
class EoriAddaDecodeTraceSpec extends AnyFunSuite {
  test("trace: EORI.L-mem then ADDA.L decode (task #140)", VerilatorTest) {
    val path = "/home/qwertyoruiop/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/repros/eori-mem-then-adda-spurious-illegal.s"
    val src  = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }

    val simSeed = 1
    val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
    scala.util.Random.setSeed(simSeed)
    compiled.doSim("eoriadda", simSeed) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      var cycleCount = 0
      cd.onSamplings {
        cycleCount += 1
        val fedV = dut.dec.logic.fed.valid.toBoolean
        val fedR = dut.dec.logic.fed.ready.toBoolean
        if (fedV) {
          val pc0 = dut.dec.logic.fed.payload.packets(0).pc.toLong & 0xffffffffL
          val simple0 = dut.dec.logic.fed.payload.packets(0).simple.toBoolean
          val fault0 = dut.dec.logic.fed.payload.packets(0).fault.toBoolean
          val lenW0 = dut.dec.logic.fed.payload.packets(0).lenWords.toInt
          val w0a = dut.dec.logic.fed.payload.packets(0).words(0).toInt & 0xffff
          val w0b = dut.dec.logic.fed.payload.packets(0).words(1).toInt & 0xffff
          println(f"[eoriadda] FED cyc=$cycleCount%4d valid=$fedV ready=$fedR pc0=0x$pc0%08x simple0=$simple0 fault0=$fault0 lenW0=$lenW0 w0=0x$w0a%04x w1=0x$w0b%04x")
        }
        val ucA = dut.dec.logic.ucActive.toBoolean
        val ucP = dut.dec.logic.ucPc.toInt
        if (ucA) {
          println(f"[eoriadda] UC cyc=$cycleCount%4d ucActive=$ucA ucPc=$ucP")
        }
        val pushV = dut.dec.logic.pushReg.valid.toBoolean
        if (pushV) {
          val cnt = dut.dec.logic.pushReg.payload.count.toInt
          for (i <- 0 until 4) {
            val u = dut.dec.logic.pushReg.payload.uops(i)
            val upc = u.pc.toLong & 0xffffffffL
            val faulted = u.faulted.toBoolean
            val fvec = u.faultVector.toInt
            val first = u.firstOfInstr.toBoolean
            if (i < cnt || faulted) {
              println(f"[eoriadda] PUSH cyc=$cycleCount%4d i=$i pc=0x$upc%08x faulted=$faulted vec=$fvec first=$first cnt=$cnt")
            }
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
      // DE|IE -- "firmware already enabled the caches", the posture every other
      // full-core harness in this repo states explicitly (design doc section 5.2).
      // Stated here too as of 2026-09-09: CACR.IE acquired its FIRST reader that
      // day (IcachePlugin), so a bespoke sim that leaves CACR at its reset value 0
      // now runs with the instruction cache DISABLED. Correct, but not what this
      // test means to exercise -- and silently so.
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
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

      cd.waitSampling(300)
      println(f"[eoriadda] DONE cyc=$cycleCount")
    }
  }
}
