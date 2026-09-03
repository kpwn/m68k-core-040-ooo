package m68k040.fuzz

import m68k040.{M68kSim, VerilatorTest}
import m68k040.oracle.ProgramAssembler
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Fuzz cluster E (seed 21) -- DIRECT capture of the store address the LS EU actually
  * computes for `move.w #imm,(bd,An,Xn.l*4)`.
  *
  * The campaign established by program-level probing that the EA arithmetic is correct
  * (`lea` of the identical EA publishes 0x00004022) and that the register-source store to
  * the same destination shape is correct, but every candidate wrong address lands OUTSIDE
  * the lock-step-compared sandbox once `An + bd` overflows 2^32 -- so where the store goes
  * is unobservable from the program. This taps `LsEuPlugin`'s `s1Va` (and its three input
  * terms) directly instead.
  *
  * Two stores to the SAME destination EA shape, differing ONLY in source operand type. */
class ClusterEAddrCaptureSpec extends AnyFunSuite {
  test("capture: LS-EU s1Va for MOVE #imm vs MOVE Dn to an indexed dst (cluster E)", VerilatorTest) {
    val src =
      """	move.l #0xffffffff,%d6
        |	move.l #0xffff3ff0,%a3
        |	move.w #0x1234,%d1
        |	move.w #0x80,(0x10036,%a3,%d6.l*4)
        |	move.w %d1,(0x10036,%a3,%d6.l*4)
        |Lend:
        |	bra.s Lend
        |""".stripMargin

    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    println(f"[clusterE] loadAddr=0x$loadAddr%08x imageBytes=${image.bytes.length}")

    val simSeed = 1
    val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
    scala.util.Random.setSeed(simSeed)
    compiled.doSim("clusterE", simSeed) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      var cyc = 0
      var lastKey = ""
      cd.onSamplings {
        cyc += 1
        if (dut.lsEu.logic.s1Valid.toBoolean) {
          val pc    = dut.lsEu.logic.s1Ctx.uop.pc.toLong & 0xffffffffL
          val base  = dut.lsEu.logic.s1Base.toLong & 0xffffffffL
          val disp  = dut.lsEu.logic.s1Disp.toLong
          val index = dut.lsEu.logic.s1Index.toLong & 0xffffffffL
          val va    = dut.lsEu.logic.s1Va.toLong & 0xffffffffL
          val data  = dut.lsEu.logic.s1Data.toLong & 0xffffffffL
          val imm   = dut.lsEu.logic.s1Ctx.uop.imm.toLong & 0xffffffffL
          val uImm  = dut.lsEu.logic.s1Ctx.uop.useImm.toBoolean
          val rob   = dut.lsEu.logic.s1Ctx.robId.toInt
          val key = f"$pc%08x/$base%08x/$disp%d/$index%08x/$va%08x/$data%08x/$imm%08x"
          if (key != lastKey) {
            lastKey = key
            println(f"[clusterE] cyc=$cyc%5d rob=$rob%2d pc=0x$pc%08x base=0x$base%08x " +
                    f"disp=0x${disp & 0xffffffffL}%08x(${disp}) index=0x$index%08x " +
                    f"=> s1Va=0x$va%08x  data=0x$data%08x imm=0x$imm%08x useImm=$uImm")
          }
        }
      }

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0
      dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
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
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      cd.waitSampling(400)
      println(f"[clusterE] DONE cyc=$cyc")
    }
  }
}
