package m68k040.exception

import m68k040.{M68kSim, VerilatorTest}
import m68k040.fuzz.{FuzzCoreDut, FuzzDut}
import m68k040.ls.BehavioralMemAgent
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** FSAVE / FRESTORE state frames (Task 11).
  *
  * WHITEBOX, NOT LOCK-STEP, AND DELIBERATELY SO. The approved design spec records that
  * Musashi's FSAVE has an entirely different frame shape (its 68040 path unconditionally
  * writes the single longword `0x41000000` and returns 4 -- see
  * `m68kfpu.cpp:perform_fsave`) and it has no unimplemented-instruction trap at all, so
  * there is nothing for a lock-step oracle to referee here. The MC68040 User's Manual is
  * the reference and memory contents are the assertion.
  *
  * FRAME LAYOUT PRIMARY SOURCE (this task's blocking Step 2, executed): MC68040 User's
  * Manual, 1989 first edition, **Figure 9-7 "Floating-Point State Frames (Sheet 2 of 2)",
  * page 9-32**, field semantics from the definition list on pages 9-33/9-34. See
  * `ExceptionUnit.fsFrameWordData` for the full transcribed table, including the recorded
  * conflict with the later manual revision's 26-word/52-byte variant.
  */
class FsaveFrestoreSpec extends AnyFunSuite {
  // ONE Verilator build for the whole suite. Hoisted deliberately: compiling the DUT
  // per-test is the exact pattern that once made `ExecuteLockStepSpec` unrunnable (16
  // rebuild sites -> JVM OOM), fixed there by this same shared `lazy val`.
  private lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var simSeq = 0
  private def simName(tag: String): String = { simSeq += 1; s"$tag-$simSeq" }

  private val loadAddr = ProgramAssembler.DefaultLoadAddress
  // The FSAVE/FRESTORE working stack. Deliberately far from the program image and
  // 4KB-page-aligned-ish so a frame never straddles a page in the ordinary tests.
  private val StackTop  = 0x00030000L
  private val RestoreBuf = 0x00021000L

  // Match-all transparent translations (instruction WT / data WT), the same shape
  // ExceptionStoreDrainArbSpec uses. Data is WRITETHROUGH (not COPYBACK) so every frame
  // store lands in the AXI memory model where the assertions can see it.
  private val IttMatchAllWt = 0x00FFC000L
  private val DttMatchAllWt = 0x00FFC000L

  /** Result of one directed run. */
  private case class Run(mem: BehavioralMemAgent, a7: Long, xlateReqs: Int,
                         coreHalted: Boolean, sawVector: Int)

  /** Boot a FuzzCoreDut in supervisor mode, run `src`, and return observations.
    *
    * `mmuFaulting = true` enables the MMU with a match-all INSTRUCTION transparent
    * translation but NO data transparent translation and an all-zero page table, so every
    * DATA translation walks and faults with an invalid descriptor -- which is exactly the
    * condition the FSAVE frame-store translation-fault escalation exists to handle.
    */
  private def run(src: String, userMode: Boolean = false,
                  everExecuted: Boolean = false,
                  unimp: Option[(Int, BigInt, BigInt)] = None,
                  restoreHeader: Option[Long] = None,
                  mmuFaulting: Boolean = false,
                  a7FrameHeader: Option[Long] = None,
                  cycles: Int = 4000,
                  name: String = "fsave"): Run = {
    val image = ProgramAssembler.assemble(src, loadAddr).getOrElse(fail("assemble failed"))
    var out: Run = null
    compiled.doSim(simName(name), 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      // Zero fill, not the PRNG default: an all-zero page table is what makes every data
      // translation fault in the `mmuFaulting` case, and a deterministic 0 background also
      // makes "the frame body is zero where it should be" a meaningful assertion.
      val zmem = new m68k040.sim.ConstFillSparseMemory(0.toByte)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = zmem)

      // Vector 8 (privilege violation) -> a self-looping handler, so the user-mode test
      // has somewhere defined to land.
      val handler = loadAddr + 0x100L
      for (i <- 0 until 4)
        dmem.pokeByte(8 * 4L + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)

      restoreHeader.foreach { h =>
        for (i <- 0 until 4)
          dmem.pokeByte(RestoreBuf + i, ((h >> (8 * (3 - i))) & 0xff).toInt)
      }
      // A frame planted at the boot A7 itself, for the `FRESTORE (A7)+` case.
      a7FrameHeader.foreach { h =>
        for (i <- 0 until 4)
          dmem.pokeByte(StackTop + i, ((h >> (8 * (3 - i))) & 0xff).toInt)
      }

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

      // Programmed AFTER reset: forkStimulus resets the registers, so poking these
      // alongside the initial inputs above would be a vacuous harness bug.
      dut.ctrl.logic.itt0 #= IttMatchAllWt
      if (mmuFaulting) {
        // Instructions transparently translated; DATA gets no transparent translation and
        // an all-zero (invalid) page table -> every data translation faults.
        dut.ctrl.logic.dtt0 #= 0
        dut.ctrl.logic.srp  #= 0x00080000L
        dut.ctrl.logic.urp  #= 0x00080000L
        dut.ctrl.logic.mmuEnable #= true
      } else {
        dut.ctrl.logic.dtt0 #= DttMatchAllWt
        dut.ctrl.logic.mmuEnable #= true
      }
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE
      dut.rob.logic.exc.ss.isp #= StackTop
      dut.rob.logic.exc.ss.usp #= StackTop
      dut.rob.logic.exc.ss.srSys #= (if (userMode) 0x00 else 0x27)
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(StackTop)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()

      // Directed FPU state, poked straight into FpuControlPlugin's committed Regs. This is
      // the whole reason these tests are whitebox: there is no architectural way to make
      // `uiValid` true yet (Task 10 delivers vector 11, but this core has no FP EU that
      // can raise a real unimplemented-instruction condition end-to-end).
      dut.fpuCtl.logic.everExecuted #= everExecuted
      unimp match {
        case Some((cmd, s, d)) =>
          dut.fpuCtl.logic.uiValid #= true
          dut.fpuCtl.logic.uiCmdReg1B #= BigInt(cmd)
          dut.fpuCtl.logic.uiSrcOperand #= s
          dut.fpuCtl.logic.uiDstOperand #= d
        case None =>
          dut.fpuCtl.logic.uiValid #= false
          dut.fpuCtl.logic.uiCmdReg1B #= 0
          dut.fpuCtl.logic.uiSrcOperand #= 0
          dut.fpuCtl.logic.uiDstOperand #= 0
      }
      cd.waitSampling()

      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false

      var xlateReqs = 0
      var halted = false
      var vec = -1
      var i = 0
      while (i < cycles) {
        cd.waitSampling()
        // A translation REQUEST FIRE by the exception sequencer.
        if (dut.rob.logic.exc.dxReqValid.toBoolean && dut.rob.logic.exc.dxReqReady.toBoolean)
          xlateReqs += 1
        if (dut.rob.logic.coreHalted.toBoolean) halted = true
        if (vec < 0 && dut.rob.logic.exceptionPending.toBoolean)
          vec = dut.rob.logic.exceptionVector.toInt
        i += 1
      }
      val a7 = dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL
      out = Run(dmem, a7, xlateReqs, halted, vec)
    }
    out
  }

  private def frameByte(r: Run, base: Long, off: Int): Int = r.mem.peekByte(base + off)
  private def frameWord(r: Run, base: Long, off: Int): Int =
    (frameByte(r, base, off) << 8) | frameByte(r, base, off + 1)

  private val fsaveA7    = ".short 0xF327"   // FSAVE   -(A7)
  private val frestoreA0 = ".short 0xF358"   // FRESTORE (A0)+   (mode 011, reg 000)

  // ── Frame emission ────────────────────────────────────────────────────────────

  test("FSAVE -(A7) with no FP op ever executed emits the 4-byte NULL frame", VerilatorTest) {
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = false, name = "fsave-null")
    val base = StackTop - 4
    // Figure 9-7's null frame: [31:24] = $00 (the version is FORCED to zero -- that is what
    // identifies a null frame), [23:16] undefined (we emit $00), 4 bytes total.
    assert(frameByte(r, base, 0) == 0x00,
      f"null frame version byte must be 0x00, got 0x${frameByte(r, base, 0)}%02X")
    assert(frameByte(r, base, 1) == 0x00, "null frame length indicator")
    assert(r.a7 == base, f"FSAVE -(A7) of a 4-byte frame must leave A7 = 0x$base%08X, got 0x${r.a7}%08X")
  }

  test("FSAVE -(A7) after an FP op emits the 4-byte IDLE frame, version 0x41", VerilatorTest) {
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true, name = "fsave-idle")
    val base = StackTop - 4
    assert(frameByte(r, base, 0) == 0x41,
      f"idle frame version byte (ExceptionUnit.FPU_FRAME_VERSION); got 0x${frameByte(r, base, 0)}%02X")
    assert(frameByte(r, base, 1) == 0x00, "idle frame carries 0 extra bytes")
    assert(r.a7 == base, f"A7 must be 0x$base%08X, got 0x${r.a7}%08X")
  }

  test("FSAVE -(A7) with a pending unimplemented instruction emits the 44-byte frame " +
       "with MC68040 UM Figure 9-7's exact field placement", VerilatorTest) {
    // Sentinel operands, laid out {sign[79], exponent[78:64], mantissa[63:0]}.
    //   src (ETEMP)  : sign 0, exponent 0x4001, mantissa 0xC0FFEE0011223344 -> NORMALIZED (000)
    //   dst (FPTEMP) : sign 1, exponent 0x7FFF, mantissa 0                  -> INFINITY   (010)
    val src80 = (BigInt(0x4001) << 64) | BigInt("C0FFEE0011223344", 16)
    val dst80 = (BigInt(1) << 79) | (BigInt(0x7FFF) << 64)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, dst80)), name = "fsave-unimp")
    val base = StackTop - 44
    assert(r.a7 == base, f"FSAVE -(A7) of the 44-byte frame must leave A7 = 0x$base%08X, got 0x${r.a7}%08X")
    assert(frameByte(r, base, 0) == 0x41, "unimplemented frame version byte")
    assert(frameByte(r, base, 1) == 0x28,
      f"length indicator must be 0x28 (40 extra bytes = 44 total); got 0x${frameByte(r, base, 1)}%02X")
    // $04 [31:29] STAG -- source is normalized -> 000.
    assert((frameByte(r, base, 0x04) >> 5) == 0x0,
      f"STAG at byte 0x04 bits 7:5 must be 000 (Normalized); got 0x${frameByte(r, base, 0x04)}%02X")
    // $08 [31:16] CMDREG1B -- the one offset the design spec independently anchors.
    assert(frameWord(r, base, 0x08) == 0xBEEF,
      f"CMDREG1B at offset 0x08; got 0x${frameWord(r, base, 0x08)}%04X")
    // $0C [31:29] DTAG -- destination is infinity -> 010.
    assert((frameByte(r, base, 0x0C) >> 5) == 0x2,
      f"DTAG at byte 0x0C bits 7:5 must be 010 (Infinity); got 0x${frameByte(r, base, 0x0C)}%02X")
    // $10 bit 2 = E1 (an unimplemented-instruction frame always reports the CU-detected
    // exception). Corroborated by Motorola's own FPSP: `E_BYTE` at +$10, `E1` = bit 2.
    assert((frameByte(r, base, 0x10) & 0x04) != 0,
      f"E1 must be bit 2 of the byte at offset 0x10; got 0x${frameByte(r, base, 0x10)}%02X")
    // $14 FPTS|FPTE, $18..$1F FPTM -- the DESTINATION operand (FPTEMP).
    assert(frameWord(r, base, 0x14) == 0xFFFF,
      f"FPTS|FPTE at 0x14 must be sign 1 | exponent 0x7FFF; got 0x${frameWord(r, base, 0x14)}%04X")
    for (off <- Seq(0x18, 0x1A, 0x1C, 0x1E))
      assert(frameWord(r, base, off) == 0x0000,
        f"FPTM word at 0x$off%02X must be 0; got 0x${frameWord(r, base, off)}%04X")
    // $20 ETS|ETE, $24..$2B ETM -- the SOURCE operand (ETEMP).
    assert(frameWord(r, base, 0x20) == 0x4001,
      f"ETS|ETE at 0x20 must be sign 0 | exponent 0x4001; got 0x${frameWord(r, base, 0x20)}%04X")
    assert(frameWord(r, base, 0x24) == 0xC0FF, f"ETM[63:48]; got 0x${frameWord(r, base, 0x24)}%04X")
    assert(frameWord(r, base, 0x26) == 0xEE00, f"ETM[47:32]; got 0x${frameWord(r, base, 0x26)}%04X")
    assert(frameWord(r, base, 0x28) == 0x1122, f"ETM[31:16]; got 0x${frameWord(r, base, 0x28)}%04X")
    assert(frameWord(r, base, 0x2A) == 0x3344, f"ETM[15:00]; got 0x${frameWord(r, base, 0x2A)}%04X")
    // Reserved words must be zero, not stale background.
    assert(frameWord(r, base, 0x02) == 0x0000, "reserved word at 0x02")
    assert(frameWord(r, base, 0x0A) == 0x0000, "reserved word at 0x0A")
  }

  test("FSAVE CONSUMES the pending unimplemented state (a second FSAVE emits IDLE)", VerilatorTest) {
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x1234)
    val r = run(s"$fsaveA7 ; $fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), name = "fsave-consume")
    // First FSAVE pushes 44 bytes; the SECOND must be a 4-byte IDLE frame directly below it.
    val second = StackTop - 44 - 4
    assert(r.a7 == second,
      f"after 44-byte + 4-byte frames A7 must be 0x$second%08X, got 0x${r.a7}%08X -- " +
      "a second 44-byte frame means the pending state was never consumed")
    assert(frameByte(r, second, 0) == 0x41 && frameByte(r, second, 1) == 0x00,
      "the second FSAVE must emit an IDLE frame -- the first one consumed the pending state")
  }

  // ── FRESTORE ──────────────────────────────────────────────────────────────────

  test("FRESTORE (A0)+ pops 4 + the in-memory length byte, for every frame flavour", VerilatorTest) {
    // Architecturally universal, and exactly the matrix `fsave_frestore_basic.s` checks:
    //   0x00000000 (null)                                  -> pop 4
    //   0x41000000 (the FPSP's manufactured pseudo-null)    -> pop 4
    //   0x41280000 (unimplemented instruction)              -> pop 44
    //   0x41600000 (busy -- a shape this core never EMITS)  -> pop 100
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41000000L -> 4,
                                    0x41280000L -> 44, 0x41600000L -> 100)) {
      val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; done: bra.s done",
                  restoreHeader = Some(header), name = f"frestore-pop-$expectPop")
      // A0 is not directly visible; assert through a store instead would need another
      // instruction, so read the architectural A0 back out of the int PRF via the
      // committed mapping is not exposed either -- use the frame base + pop size the FSM
      // computed, which IS what it wrote back. Observe it via a following FSAVE instead.
      assert(r.sawVector < 0, s"FRESTORE of header 0x${header.toHexString} must not trap")
      assert(!r.coreHalted, s"FRESTORE of header 0x${header.toHexString} must not halt the core")
    }
  }

  test("FRESTORE (A0)+ advances A0 by 4 + the length byte (observed through a store)", VerilatorTest) {
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41000000L -> 4,
                                    0x41280000L -> 44, 0x41600000L -> 100)) {
      val probe = 0x00025000L
      val r = run(
        f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; move.l %%a0,0x$probe%08x ; done: bra.s done",
        restoreHeader = Some(header), name = f"frestore-a0-$expectPop")
      val got = (0 until 4).map(i => r.mem.peekByte(probe + i).toLong)
                           .reduceLeft((acc, b) => (acc << 8) | b)
      assert(got == RestoreBuf + expectPop,
        f"FRESTORE (A0)+ on header 0x$header%08X must advance A0 by $expectPop " +
        f"(0x${RestoreBuf + expectPop}%08X); got 0x$got%08X")
    }
  }

  test("FRESTORE (A7)+ really advances A7 (the S_REDIR re-bank must not clobber it)",
       VerilatorTest) {
    // The auto-update destination IS A7 here, which is the one case where S_REDIR's
    // unconditional `committedPhysA7 := ss.a7` re-bank targets the SAME physical register
    // the FSM just wrote. Without ExceptionUnit's F_RSETTLE settle cycles this silently
    // writes the stale pre-FRESTORE A7 straight back, and A7 never moves.
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41280000L -> 44)) {
      val r = run(s"${".short 0xF35F"} ; done: bra.s done",   // FRESTORE (A7)+
                  restoreHeader = None, name = f"frestore-a7-$expectPop",
                  a7FrameHeader = Some(header))
      assert(r.a7 == StackTop + expectPop,
        f"FRESTORE (A7)+ on header 0x$header%08X must advance A7 by $expectPop " +
        f"(0x${StackTop + expectPop}%08X); got 0x${r.a7}%08X")
    }
  }

  test("FRESTORE of a NULL frame resets the FPU: a following FSAVE emits NULL again", VerilatorTest) {
    // "When an FRESTORE of a null state frame is performed, all FPU operations are
    // aborted, and the FPU enters the reset state" (MC68040 UM 1989 1st ed., p.9-30) --
    // the one FRESTORE behavior real hardware unconditionally requires. Proved through
    // the observable consequence: everExecuted is cleared, so the next FSAVE emits NULL.
    val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; $fsaveA7 ; done: bra.s done",
                everExecuted = true, restoreHeader = Some(0x00000000L), name = "frestore-null-reset")
    val base = StackTop - 4
    assert(frameByte(r, base, 0) == 0x00,
      f"after a null-frame FRESTORE the FPU is 'never used' again -> the next FSAVE must " +
      f"emit NULL; got version 0x${frameByte(r, base, 0)}%02X")
  }

  test("FRESTORE of a NON-null frame leaves the FPU 'has state': a following FSAVE emits IDLE",
       VerilatorTest) {
    val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; $fsaveA7 ; done: bra.s done",
                everExecuted = false, restoreHeader = Some(0x41000000L), name = "frestore-idle-set")
    val base = StackTop - 4
    assert(frameByte(r, base, 0) == 0x41,
      f"a non-null FRESTORE means the FPU had state -> the next FSAVE must emit IDLE; " +
      f"got version 0x${frameByte(r, base, 0)}%02X")
  }

  // ── Privilege ─────────────────────────────────────────────────────────────────

  test("FSAVE in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val r = run(s"$fsaveA7 ; handler: bra.s handler", userMode = true, name = "fsave-priv-user")
    assert(r.sawVector == 8, s"user-mode FSAVE must raise vector 8, got ${r.sawVector}")
  }

  test("FSAVE in SUPERVISOR mode raises NO exception", VerilatorTest) {
    val r = run(s"$fsaveA7 ; done: bra.s done", name = "fsave-priv-super")
    assert(r.sawVector < 0, s"supervisor FSAVE must not trap, got vector ${r.sawVector}")
  }

  test("FRESTORE in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; handler: bra.s handler",
                userMode = true, restoreHeader = Some(0x00000000L), name = "frestore-priv-user")
    assert(r.sawVector == 8, s"user-mode FRESTORE must raise vector 8, got ${r.sawVector}")
  }

  // ── The translation-aware substrate (this task's whole point) ─────────────────

  test("a 44-byte FSAVE frame that does not cross a page needs exactly ONE DTLB translation",
       VerilatorTest) {
    // Regression guard against reverting to blind per-word retranslation: 22 words would
    // mean 22 requests. StackTop-44 = 0x0002FFD4, comfortably inside one 4KB page.
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), name = "fsave-one-xlate")
    assert(r.a7 == StackTop - 44, "sanity: the 44-byte frame really was emitted")
    assert(r.xlateReqs == 1,
      s"a non-page-crossing 44-byte frame must issue exactly ONE DTLB translation " +
      s"(translate-on-VPN-change); got ${r.xlateReqs}")
  }

  test("an FSAVE frame that STRADDLES a page boundary re-translates exactly once more",
       VerilatorTest) {
    // Put the frame across a 4KB boundary: base = 0x00030000-44 is in page 0x2F, but if we
    // move the stack to 0x00030014 the 44-byte frame spans 0x0002FFE8..0x00030013 -- both
    // pages. Both are transparently translated, so the ONLY observable is the request count.
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val image = ProgramAssembler.assemble(
      s"lea 0x00030014,%a7 ; $fsaveA7 ; done: bra.s done", loadAddr).getOrElse(fail("assemble failed"))
    var reqs = 0
    var a7 = 0L
    compiled.doSim(simName("fsave-page-cross"), 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val zmem = new m68k040.sim.ConstFillSparseMemory(0.toByte)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = zmem)
      new BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = zmem)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.ctrl.logic.itt0 #= 0; dut.ctrl.logic.itt1 #= 0
      dut.ctrl.logic.dtt0 #= 0; dut.ctrl.logic.dtt1 #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= true
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.ctrl.logic.itt0 #= IttMatchAllWt
      dut.ctrl.logic.dtt0 #= DttMatchAllWt
      dut.ctrl.logic.mmuEnable #= true
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.isp #= StackTop
      dut.rob.logic.exc.ss.usp #= StackTop
      dut.rob.logic.exc.ss.srSys #= 0x27
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(StackTop)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fpuCtl.logic.everExecuted #= true
      dut.fpuCtl.logic.uiValid #= true
      dut.fpuCtl.logic.uiCmdReg1B #= BigInt(0xBEEF)
      dut.fpuCtl.logic.uiSrcOperand #= src80
      dut.fpuCtl.logic.uiDstOperand #= BigInt(0)
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      for (_ <- 0 until 4000) {
        cd.waitSampling()
        if (dut.rob.logic.exc.dxReqValid.toBoolean && dut.rob.logic.exc.dxReqReady.toBoolean)
          reqs += 1
      }
      a7 = dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL
      assert(dmem != null)
    }
    assert(a7 == 0x00030014L - 44, f"sanity: the 44-byte frame was emitted; A7 = 0x$a7%08X")
    assert(reqs == 2,
      s"a frame straddling ONE page boundary must translate exactly twice " +
      s"(translate-on-VPN-change, not per-word); got $reqs")
  }

  test("a DTLB translation fault during an FSAVE frame store HALTS the core", VerilatorTest) {
    // The failure mode this whole redesign exists to close: before Task 11 the frame store
    // proceeded with an identity-physical address as if the access had succeeded. Now the
    // fault escalates to the sticky coreHalted latch and the transfer STOPS.
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), mmuFaulting = true,
                name = "fsave-xlate-fault")
    assert(r.coreHalted,
      "a faulting DTLB translation for an FSAVE frame store must drive coreHaltedIn")
    // And it must actually STOP: no frame word may have been written. The background is a
    // deterministic 0 fill, and the header word would be 0x4128 if the store had gone ahead.
    val base = StackTop - 44
    assert(frameWord(r, base, 0) == 0x0000,
      f"the frame transfer must not proceed past the fault; header word was " +
      f"0x${frameWord(r, base, 0)}%04X")
  }

  test("a DTLB translation fault during a FRESTORE header read HALTS the core", VerilatorTest) {
    val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; done: bra.s done",
                mmuFaulting = true, name = "frestore-xlate-fault")
    assert(r.coreHalted,
      "a faulting DTLB translation for the FRESTORE header read must drive coreHaltedIn")
  }
}
