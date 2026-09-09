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
  // The two stores are identified by their STORE DATA, not by PC: `s1Data` is the one
  // field that distinguishes them unambiguously, and it does not move if the assembler's
  // instruction lengths or the prologue ever change.
  private val ImmStoreData = 0x00000080L   // move.w #0x80,<ea>   -- the failing form
  private val RegStoreData = 0x00001234L   // move.w %d1,<ea>     -- the control
  // A3 + 0x10036 = 0x1_00004026, truncated to 0x00004026, + (-1 * 4) = 0x00004022.
  private val CorrectEa  = 0x00004022L
  private val CorrectBd  = 0x00010036L
  // What the DUT computed before the fix, measured off this exact tap: the long base
  // displacement lost its LOW half-word (0x00010036 -> 0x00010000), sending the store
  // 0x36 bytes low. Named here so a regression is recognised, not merely reported.
  private val BuggyEa    = 0x00003fecL
  private val BuggyBd    = 0x00010000L

  test("whitebox: MOVE #imm and MOVE Dn compute the SAME s1Va for a long-bd indexed dst (cluster E)", VerilatorTest) {
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
      // store data -> (s1Va, s1Disp) for the two stores under test.
      val seen = scala.collection.mutable.LinkedHashMap[Long, (Long, Long)]()
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
          // Record the FIRST S1 sample per store (the address is settled at S1;
          // s1Base/s1Ctx are held stable while the FSM is busy, so later cycles repeat it).
          if ((data == ImmStoreData || data == RegStoreData) && !seen.contains(data))
            seen(data) = (va, disp & 0xffffffffL)
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
      // IE only -- deliberately NOT DE. As of 2026-09-09 CACR.IE acquired its FIRST
      // reader (IcachePlugin), so a bespoke sim that leaves CACR at its reset value
      // of 0 silently starts running with the INSTRUCTION cache disabled. This poke
      // restores exactly the posture this test had before that change: I-cache on
      // (it was unconditionally enabled), D-cache off (DE was already 0).
      //
      // DE MUST STAY 0 HERE. These harnesses observe individual AXI frame writes at
      // their exact word addresses; enabling the D-cache coalesces them into
      // line-granular writebacks and the instrument reads one line base instead of
      // four frame words. Setting DE|IE here broke 8 of M1ThrowawayFrameIrqSpec's
      // tests for exactly that reason.
      dut.rob.logic.exc.ss.cacr #= 0x00008000L
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

      def check(label: String, storeData: Long): Unit = {
        val (va, disp) = seen.getOrElse(storeData,
          fail(f"$label store (data=0x$storeData%08x) never reached LS-EU S1 -- the program " +
               f"changed, or it no longer routes through the AGU. Re-read the capture log " +
               f"above before touching these expectations."))
        assert(disp == CorrectBd,
          f"$label: base displacement decoded as 0x$disp%08x, expected 0x$CorrectBd%08x" +
          (if (disp == BuggyBd) " -- the long bd lost its LOW half-word (fuzz cluster E)" else ""))
        assert(va == CorrectEa,
          f"$label: s1Va = 0x$va%08x, expected 0x$CorrectEa%08x" +
          (if (va == BuggyEa) " -- this is cluster E's exact pre-fix address" else ""))
      }
      // The control first: if the REGISTER-source form ever regresses, the immediate-source
      // assertion below would otherwise be misread as cluster E returning.
      check("register-source", RegStoreData)
      check("immediate-source", ImmStoreData)
    }
  }
}
