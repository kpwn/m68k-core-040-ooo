package m68k040.fuzz

import m68k040.M68kSim
import m68k040.VerilatorTest
import m68k040.oracle.ProgramAssembler
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import java.nio.file.{Files, Paths}

/** FmovemBranchTraceSpec — why does a conditional branch shortly after an FMOVEM.X
  * store fall through when it should be taken?
  *
  * Reproducer: src/test/resources/m68kooo-ported-tests/asm/fpu_store_then_cmp_bcc.s
  * (committed ae5175c2, known-failing).  Assembly bisection established:
  *   - the stored data is correct, and CMP sets Z correctly (Scc reads 0xFF)
  *   - only Bcc misbehaves, and only after FMOVEM.X (FMOVE.X is fine)
  *   - enough separation / an intervening store / any control transfer closes it
  * but could not distinguish "resolved not-taken" from "resolved taken, redirect lost".
  *
  * `BranchEuPlugin.wbObs` already carries the RESOLVED `nextPc`, so that question is
  * answerable with no RTL change:
  *   nextPc == branch target  -> the branch DID resolve taken; the redirect/flush was
  *                               lost downstream.
  *   nextPc == fall-through   -> the branch resolved NOT taken; the condition itself
  *                               was evaluated wrong.
  * Cross-checked against the committed-PC stream (rob.commitObs), which shows which
  * path actually retired.
  */
class FmovemBranchTraceSpec extends AnyFunSuite {
  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)

  test("fmovem.x store then cmp+beq: did the branch resolve taken?", VerilatorTest) {
    val src = new String(Files.readAllBytes(Paths.get(
        "src/test/resources/m68kooo-ported-tests/asm/fpu_store_then_cmp_bcc.s")))
    val image = ProgramAssembler.assemble(src, PortedTestRunner.loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble: ${e.reason}")
    }

    compiled.doSim("fmovem_branch_trace", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, PortedTestRunner.loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = true)
      for (i <- image.bytes.indices) dmem.mem.write(PortedTestRunner.loadAddr + i, image.bytes(i).toByte)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false; dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0L
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= PortedTestRunner.loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      for (i <- 0 until 4) dmem.mem.write(PortedTestRunner.SentinelAddr + i, 0)
      val rl = dut.rob.logic
      val wb = dut.branchEu.logic.wbObs
      val be = dut.branchEu.logic
      val nzW = dut.rfNzvc.logic.dbgW
      val rn  = dut.ren.logic.rnDbg
      var cyc = 0L
      var word = 0L
      val commits = scala.collection.mutable.ArrayBuffer[(Long, Long)]()   // (cyc, pc)
      val brResolves = scala.collection.mutable.ArrayBuffer[(Long, Int, Long)]() // (cyc, robId, nextPc)

      while (word == 0 && cyc < 20000) {
        cd.waitSampling(); cyc += 1
        if (wb.valid.toBoolean) {
          brResolves += ((cyc, wb.robId.toInt, wb.nextPc.toLong & 0xffffffffL))
        }
        for (i <- 0 until 2) {
          val d = rn(i)
          if (d.valid.toBoolean && (d.readsNzvc.toBoolean || d.writesNzvc.toBoolean)) {
            println(f"[FBT] RN slot$i pc=0x${d.pc.toLong & 0xffffffffL}%08x " +
                    f"rd=${if (d.readsNzvc.toBoolean) "Y" else "n"} src=${d.pNzvcSrc.toInt}%2d " +
                    f"wr=${if (d.writesNzvc.toBoolean) "Y" else "n"} dst=${d.pNzvcDst.toInt}%2d (cyc=$cyc)")
          }
        }
        for (i <- 0 until nzW.length) {
          val w = nzW(i)
          if (w.valid.toBoolean) {
            val d = w.data.toInt
            println(f"[FBT] NZVC wr port$i phys=${w.address.toInt}%2d " +
                    f"<= N${(d>>3)&1}Z${(d>>2)&1}V${(d>>1)&1}C${d&1}  (cyc=$cyc)")
          }
        }
        val bd = be.brDbg
        if (bd.valid.toBoolean && bd.readsNz.toBoolean) {
          val nz = bd.nzvc.toInt
          println(f"[FBT] S1 br pc=0x${bd.pc.toLong & 0xffffffffL}%08x cond=${bd.cond.toInt}%2d " +
                  f"nzAddr=${bd.nzAddr.toInt}%2d NZVC=N${(nz>>3)&1}Z${(nz>>2)&1}V${(nz>>1)&1}C${nz&1} " +
                  f"taken=${bd.taken.toBoolean} redir=${bd.redirect.toBoolean} next=0x${bd.nextPc.toLong & 0xffffffffL}%08x")
        }
        for (k <- 0 until 2) {
          val c = rl.commitObs(k)
          if (c.fire.toBoolean) commits += ((cyc, c.pc.toLong & 0xffffffffL))
        }
        val b = (0 until 4).map(i => dmem.mem.read(PortedTestRunner.SentinelAddr + i).toLong & 0xffL)
        word = (b(0) << 24) | (b(1) << 16) | (b(2) << 8) | b(3)
      }

      println(f"[FBT] sentinel=0x$word%08x after $cyc cycles")
      println("[FBT] ---- branch resolutions (cyc, robId, resolved nextPc) ----")
      brResolves.takeRight(24).foreach { case (c, r, n) => println(f"[FBT]   cyc=$c%6d rob=$r%2d nextPc=0x$n%08x") }
      println("[FBT] ---- committed PCs (last 40) ----")
      commits.takeRight(40).foreach { case (c, p) => println(f"[FBT]   cyc=$c%6d pc=0x$p%08x") }
    }
  }
}
