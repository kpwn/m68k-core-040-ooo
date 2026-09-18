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
  * FRAME LAYOUT PRIMARY SOURCE: the unimplemented-instruction frame is the **26-word /
  * 52-byte** shape of the MC68040 UM's LATER revision (M68040UM/AD rev 1, section 9.7,
  * **Figure 9-10 sheet 2, sub-figure (d)**, length code `$30`) -- "mask rev B" -- chosen by
  * explicit user decision over the 1989 first edition's 22-word/44-byte Figure 9-7, because
  * it is what the real Quadra 700 Mac ROM FPSP is written against. Field offsets are pinned
  * against a live disassembly of that ROM; see `ExceptionUnit.fsFrameWordData`'s table for
  * the per-row citations and the honest confidence labelling. Null and idle frames are
  * identical between the two manual editions and are unaffected.
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
                         coreHalted: Boolean, sawVector: Int,
                         // 2026-09-09: the two halt causes, separately, so a test can say
                         // WHICH one fired. `fsXlateFault` is the FSAVE/FRESTORE
                         // state-frame escalation; `dblFault` is a fault taken while
                         // already in exception processing.
                         fsXlateFault: Boolean, dblFault: Boolean,
                         // `dblFaultVec` latched at the double fault: WHICH vector's entry
                         // was being processed when the second fault hit. That is the only
                         // observable proof of an INTERNALLY synthesized entry -- the ROB's
                         // `exceptionPending` only fires for exceptions an INSTRUCTION
                         // raises, so `sawVector` cannot see one (the pre-existing
                         // vector-14 format-error and vector-3 odd-PC re-entries are
                         // invisible to it for the same reason).
                         dblVec: Int)

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
                  // p167: place the FRESTORE frame at RestoreBuf + this byte offset, and
                  // plant `restoreDecoy` at the enclosing 16-byte LINE BASE. A header word
                  // at line-relative offset 15 that is read as one WORD comes back as
                  // {byte[15], byte[0]} of the same line -- i.e. with the decoy's high byte
                  // as its LENGTH byte -- so the decoy makes a wrapped read unmistakable.
                  restoreOff: Int = 0,
                  restoreDecoy: Option[Long] = None,
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
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)

      // Vector 8 (privilege violation) -> a self-looping handler, so the user-mode test
      // has somewhere defined to land.
      val handler = loadAddr + 0x100L
      for (i <- 0 until 4)
        dmem.pokeByte(8 * 4L + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)

      restoreHeader.foreach { h =>
        for (i <- 0 until 4)
          dmem.pokeByte(RestoreBuf + restoreOff + i, ((h >> (8 * (3 - i))) & 0xff).toInt)
      }
      restoreDecoy.foreach { d =>
        val lineBase = (RestoreBuf + restoreOff) & ~0xfL
        for (i <- 0 until 4)
          dmem.pokeByte(lineBase + i, ((d >> (8 * (3 - i))) & 0xff).toInt)
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
      var fsFault = false
      var dblF = false
      var dblVec = -1
      var vec = -1
      var i = 0
      while (i < cycles) {
        cd.waitSampling()
        // A translation REQUEST FIRE by the exception sequencer.
        if (dut.rob.logic.exc.dxReqValid.toBoolean && dut.rob.logic.exc.dxReqReady.toBoolean)
          xlateReqs += 1
        if (dut.rob.logic.coreHalted.toBoolean) halted = true
        if (dut.rob.logic.exc.fsXlateFault.toBoolean) fsFault = true
        if (dut.rob.logic.exc.dblFault.toBoolean) {
          if (!dblF) dblVec = dut.rob.logic.exc.dblFaultVec.toInt
          dblF = true
        }
        if (vec < 0 && dut.rob.logic.exceptionPending.toBoolean)
          vec = dut.rob.logic.exceptionVector.toInt
        i += 1
      }
      val a7 = dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL
      out = Run(dmem, a7, xlateReqs, halted, vec, fsFault, dblF, dblVec)
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

  test("FSAVE pending unimplemented E1 distinguishes packed from binary sources", VerilatorTest) {
    val one = (BigInt(0x3fff) << 64) | (BigInt(1) << 63)
    // This checks only the frame discriminator. Packed operand capture/layout
    // is a separate requirement and is not established by injecting mock state.
    for ((cmd, packed) <- Seq((0x0526, false), (0x5926, false), (0x4d26, true))) {
      val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
        unimp = Some((cmd, one, one)), name = s"fsave-e1-$cmd")
      val base = StackTop - 52
      assert(r.a7 == base)
      assert(((frameByte(r, base, 0x18) & 4) != 0) == packed,
        f"CMD=$cmd%04x must set E1 iff packed=$packed")
    }
  }

  test("FSAVE -(A7) with a pending unimplemented instruction emits the 52-byte frame " +
       "with MC68040 UM Figure 9-10(d)'s exact field placement", VerilatorTest) {
    // Sentinel operands, laid out {sign[79], exponent[78:64], mantissa[63:0]}.
    //   src (ETEMP)  : sign 0, exponent 0x4001, mantissa 0xC0FFEE0011223344 -> NORMALIZED (000)
    //   dst (FPTEMP) : sign 1, exponent 0x7FFF, mantissa 0                  -> INFINITY   (010)
    val src80 = (BigInt(0x4001) << 64) | BigInt("C0FFEE0011223344", 16)
    val dst80 = (BigInt(1) << 79) | (BigInt(0x7FFF) << 64)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0x5926, src80, dst80)), name = "fsave-unimp")
    val base = StackTop - 52
    assert(r.a7 == base, f"FSAVE -(A7) of the 52-byte frame must leave A7 = 0x$base%08X, got 0x${r.a7}%08X")
    assert(frameByte(r, base, 0) == 0x41, "unimplemented frame version byte")
    // Byte-for-byte the header the Q700 ROM FPSP manufactures for itself at $4088DA52:
    // `subaw #48,%sp ; moveb #65,%sp@ ; moveb #48,%sp@(1) ; clrw %sp@(2)`.
    assert(frameByte(r, base, 1) == 0x30,
      f"length indicator must be 0x30 (48 extra bytes = 52 total); got 0x${frameByte(r, base, 1)}%02X")
    // $0C [31:29] STAG -- source is normalized -> 000.
    assert((frameByte(r, base, 0x0C) >> 5) == 0x0,
      f"STAG at byte 0x0C bits 7:5 must be 000 (Normalized); got 0x${frameByte(r, base, 0x0C)}%02X")
    // $10 [31:16] CMDREG1B -- ROM-pinned: $4088DA86 `movew %d0,%fp@(-228)` with the frame
    // base at %fp@(-244), and `b1238_fix`'s bfextu reads at the same address.
    assert(frameWord(r, base, 0x10) == 0x5926,
      f"CMDREG1B at offset 0x10; got 0x${frameWord(r, base, 0x10)}%04X")
    // $14 [31:29] DTAG -- destination is infinity -> 010. ROM-pinned: $4088DC6A/$4088DC70
    // copy %fp@(-224) and mask it with #$E0000000.
    assert((frameByte(r, base, 0x14) >> 5) == 0x2,
      f"DTAG at byte 0x14 bits 7:5 must be 010 (Infinity); got 0x${frameByte(r, base, 0x14)}%02X")
    // Motorola FPSP uni_getop interprets E1 as packed-source for vector 11.
    // FSCALE.B is not packed, so its binary operand must not enter unpack.
    assert((frameByte(r, base, 0x18) & 0x04) == 0,
      f"non-packed unimplemented frame must clear E1; got 0x${frameByte(r, base, 0x18)}%02X")
    // $1C FPTS|FPTE, $20..$27 FPTM -- the DESTINATION operand (FPTEMP).
    assert(frameWord(r, base, 0x1C) == 0xFFFF,
      f"FPTS|FPTE at 0x1C must be sign 1 | exponent 0x7FFF; got 0x${frameWord(r, base, 0x1C)}%04X")
    for (off <- Seq(0x20, 0x22, 0x24, 0x26))
      assert(frameWord(r, base, off) == 0x0000,
        f"FPTM word at 0x$off%02X must be 0; got 0x${frameWord(r, base, off)}%04X")
    // $28 ETS|ETE, $2C..$33 ETM -- the SOURCE operand (ETEMP), the LAST 12 bytes of the
    // frame. ROM-pinned: $4088DC32 `fmovemx %d0,%fp@(-204)` = frame+$28, 12 bytes.
    assert(frameWord(r, base, 0x28) == 0x4001,
      f"ETS|ETE at 0x28 must be sign 0 | exponent 0x4001; got 0x${frameWord(r, base, 0x28)}%04X")
    assert(frameWord(r, base, 0x2C) == 0xC0FF, f"ETM[63:48]; got 0x${frameWord(r, base, 0x2C)}%04X")
    assert(frameWord(r, base, 0x2E) == 0xEE00, f"ETM[47:32]; got 0x${frameWord(r, base, 0x2E)}%04X")
    assert(frameWord(r, base, 0x30) == 0x1122, f"ETM[31:16]; got 0x${frameWord(r, base, 0x30)}%04X")
    assert(frameWord(r, base, 0x32) == 0x3344, f"ETM[15:00]; got 0x${frameWord(r, base, 0x32)}%04X")
    // Reserved words must be zero, not stale background. $04/$08 are the two longwords the
    // 52-byte frame adds over the 44-byte one; this core has no write-back-stage (E3)
    // exception state, so zero there is the correct content, not a placeholder.
    assert(frameWord(r, base, 0x02) == 0x0000, "reserved word at 0x02")
    for (off <- Seq(0x04, 0x06, 0x08, 0x0A))
      assert(frameWord(r, base, off) == 0x0000,
        f"the added longwords at 0x04/0x08 must be zero (E3 = 0, no CMDREG2B/CMDREG3B); " +
        f"word at 0x$off%02X was 0x${frameWord(r, base, off)}%04X")
    assert(frameWord(r, base, 0x12) == 0x0000, "reserved word at 0x12")
  }

  test("FSAVE CONSUMES the pending unimplemented state (a second FSAVE emits IDLE)", VerilatorTest) {
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x1234)
    val r = run(s"$fsaveA7 ; $fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), name = "fsave-consume")
    // First FSAVE pushes 52 bytes; the SECOND must be a 4-byte IDLE frame directly below it.
    val second = StackTop - 52 - 4
    assert(r.a7 == second,
      f"after 52-byte + 4-byte frames A7 must be 0x$second%08X, got 0x${r.a7}%08X -- " +
      "a second 52-byte frame means the pending state was never consumed")
    assert(frameByte(r, second, 0) == 0x41 && frameByte(r, second, 1) == 0x00,
      "the second FSAVE must emit an IDLE frame -- the first one consumed the pending state")
  }

  // ── FRESTORE ──────────────────────────────────────────────────────────────────

  test("FRESTORE (A0)+ pops 4 + the in-memory length byte, for every frame flavour", VerilatorTest) {
    // Architecturally universal, and exactly the matrix `fsave_frestore_basic.s` checks:
    //   0x00000000 (null)                                     -> pop 4
    //   0x41000000 (the FPSP's manufactured pseudo-null)       -> pop 4
    //   0x41280000 (unimplemented instruction, mask rev A)     -> pop 44
    //   0x41300000 (unimplemented instruction, mask rev B --
    //               the shape this core now EMITS)             -> pop 52
    //   0x41600000 (busy -- a shape this core never EMITS)     -> pop 100
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41000000L -> 4,
                                    0x41280000L -> 44, 0x41300000L -> 52,
                                    0x41600000L -> 100)) {
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
                                    0x41280000L -> 44, 0x41300000L -> 52,
                                    0x41600000L -> 100)) {
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

  // ── p167: the header word at a 16-byte LINE BOUNDARY (odd frame base) ─────────
  //
  // `DcacheByteLane.extract` indexes `off + 1` with a 4-bit WRAPPING index, so a WORD
  // read at line-relative offset 15 returns {byte[15], byte[0]} of the SAME line. The
  // FSAVE store side has split exactly this case since task #163 (`fsSplitLow`) and the
  // RTE frame-word read side since p167 (`rdSplitLow`); FRESTORE's header read was the
  // last unguarded one (`F_HDRREQ`). It is reachable, not theoretical: `frestore (%a7)+`
  // with an ODD supervisor SP is what Mac OS does on an FPU context switch inside a
  // `link %a6,#-75` stretch (RAM 0x9008, SSP = 0x0017fec7 on the p167 Quadra 700 boot).
  //
  // A wrapped read takes the LENGTH byte from unrelated memory, so FRESTORE pops the
  // wrong number of bytes and An/A7 lands off the real frame for the rest of the program.
  // The decoy at the line base makes that unambiguous: header 0x41300000 at offset 15
  // must pop 4 + 0x30 = 52; a wrapped read sees 0x41DE and would pop 4 + 0xDE = 226.
  test("FRESTORE header word at line offset 15 reads the REAL length byte, not the " +
       "byte wrapped from the line base (p167)", VerilatorTest) {
    // Sweep every offset in the top half of the line: 15 crosses, 8..14 are controls that
    // must be unaffected by the split logic.
    for (off <- Seq(8, 12, 13, 14, 15); (header, expectPop) <- Seq(0x41300000L -> 52, 0x41280000L -> 44, 0x00000000L -> 4)) {
      val probe = 0x00025000L
      val base  = RestoreBuf + off
      val r = run(
        f"lea 0x$base%08x,%%a0 ; $frestoreA0 ; move.l %%a0,0x$probe%08x ; done: bra.s done",
        restoreHeader = Some(header), restoreOff = off, restoreDecoy = Some(0xDEADBEEFL),
        name = f"frestore-line15-$off-$expectPop")
      val got = (0 until 4).map(i => r.mem.peekByte(probe + i).toLong)
                           .reduceLeft((acc, b) => (acc << 8) | b)
      assert(got == base + expectPop,
        f"FRESTORE (A0)+ on header 0x$header%08X at line offset $off must advance A0 by " +
        f"$expectPop (0x${base + expectPop}%08X); got 0x$got%08X " +
        f"(a wrapped read would pop 4 + the decoy's 0xDE = 226)")
      assert(!r.coreHalted, f"FRESTORE at line offset $off must not halt the core")
    }
  }

  test("FRESTORE (A7)+ really advances A7 (the S_REDIR re-bank must not clobber it)",
       VerilatorTest) {
    // The auto-update destination IS A7 here, which is the one case where S_REDIR's
    // unconditional `committedPhysA7 := ss.a7` re-bank targets the SAME physical register
    // the FSM just wrote, silently writing the stale pre-FRESTORE A7 straight back so A7
    // never moves. Task 11 first worked around this with a dedicated `F_RSETTLE` settle
    // state; it is now fixed structurally for EVERY sysOp whose own destination is A7
    // (see `ExceptionUnit.sysOwnA7Valid`) and the settle state is gone. This test still
    // guards exactly the same observable behavior, now against the general fix.
    for ((header, expectPop) <- Seq(0x00000000L -> 4, 0x41300000L -> 52)) {
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

  test("a 52-byte FSAVE frame that does not cross a page needs exactly ONE DTLB translation",
       VerilatorTest) {
    // Regression guard against reverting to blind per-word retranslation: 26 words would
    // mean 26 requests. StackTop-52 = 0x0002FFCC, comfortably inside one 4KB page.
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), name = "fsave-one-xlate")
    assert(r.a7 == StackTop - 52, "sanity: the 52-byte frame really was emitted")
    assert(r.xlateReqs == 1,
      s"a non-page-crossing 52-byte frame must issue exactly ONE DTLB translation " +
      s"(translate-on-VPN-change); got ${r.xlateReqs}")
  }

  test("an FSAVE frame that STRADDLES a page boundary re-translates exactly once more",
       VerilatorTest) {
    // Put the frame across a 4KB boundary: base = 0x00030000-52 is in page 0x2F, but if we
    // move the stack to 0x00030014 the 52-byte frame spans 0x0002FFE0..0x00030013 -- both
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
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)
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
    assert(a7 == 0x00030014L - 52, f"sanity: the 52-byte frame was emitted; A7 = 0x$a7%08X")
    assert(reqs == 2,
      s"a frame straddling ONE page boundary must translate exactly twice " +
      s"(translate-on-VPN-change, not per-word); got $reqs")
  }

  // ── 2026-09-09: these two used to assert that a translation fault HALTS the core ──
  //
  // It does not, and it must not. FSAVE and FRESTORE are ORDINARY INSTRUCTIONS, and
  // halting is never the correct 68040 response to one faulting: a non-resident or
  // write-protected state-frame page is an ACCESS FAULT the OS is expected to handle
  // (page it in, RTE, the instruction re-runs). Task 11 escalated it to `fsXlateFault`
  // -> `coreHaltedIn` because there was no fault-delivery path in this unit at all; there
  // is one now (`busErrorEntry`), so the escalation is gone and these tests are INVERTED
  // rather than deleted -- the behaviour they pin is still exactly the interesting one.
  //
  // What this harness makes visible, and why the core still ends up halted: `mmuFaulting`
  // installs an ALL-ZERO page table, so EVERY data translation faults -- including the
  // vector-2 entry's own frame push. That second fault IS taken while already in
  // exception processing, so it is a genuine DOUBLE FAULT and the part genuinely halts
  // (M68040UM S8.4.2). The two flags are therefore the assertion: `fsXlateFault` must be
  // CLEAR (the instruction no longer halts on its own account) and `dblFault` must be
  // SET (the halt is attributed to the entry, not to FSAVE). That is the full escalation
  // chain -- instruction fault -> access fault -> double fault -- in one run.
  test("a DTLB translation fault during an FSAVE frame store raises vector 2, not a halt", VerilatorTest) {
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val r = run(s"$fsaveA7 ; done: bra.s done", everExecuted = true,
                unimp = Some((0xBEEF, src80, BigInt(0))), mmuFaulting = true,
                name = "fsave-xlate-fault")
    assert(!r.fsXlateFault,
      "an FSAVE state-frame translation fault must NOT take the halt escalation any more")
    assert(r.dblFault && r.coreHalted,
      "it must raise a vector-2 ACCESS FAULT entry instead; because THIS harness leaves " +
      "every page unmapped, that entry's own frame push then faults in turn -- a real " +
      "double fault, which must halt")
    assert(r.dblVec == 2,
      s"and the entry being processed at the double fault must be the ACCESS FAULT " +
      s"(vector 2); got vector ${r.dblVec}. That is the escalation chain: instruction " +
      s"fault -> access fault -> double fault.")
    // Unchanged and still the point: the transfer must STOP at the fault. The background
    // is a deterministic 0 fill; the header word would be 0x4130 had the store proceeded.
    val base = StackTop - 52
    assert(frameWord(r, base, 0) == 0x0000,
      f"the frame transfer must not proceed past the fault; header word was " +
      f"0x${frameWord(r, base, 0)}%04X")
  }

  test("a DTLB translation fault during a FRESTORE header read raises vector 2, not a halt", VerilatorTest) {
    val r = run(f"lea 0x$RestoreBuf%08x,%%a0 ; $frestoreA0 ; done: bra.s done",
                mmuFaulting = true, name = "frestore-xlate-fault")
    assert(!r.fsXlateFault,
      "a FRESTORE header-read translation fault must NOT take the halt escalation any more")
    assert(r.dblFault && r.coreHalted,
      "it must raise a vector-2 ACCESS FAULT entry instead; that entry's own frame push " +
      "then faults in turn in this all-unmapped harness -- a real double fault, halt")
    assert(r.dblVec == 2,
      s"and the entry being processed at the double fault must be the ACCESS FAULT " +
      s"(vector 2); got vector ${r.dblVec}")
  }

  /** 8 KiB PAGES: the FSAVE frame store must take PA[12] from the untranslated VA.
    *
    * `ExceptionUnit`'s F_STORE assembled `PA = fsPpn ## fsCurVa(11 downto 0)` with NO
    * `is8K` mux, while the only other two places a translated PA is assembled --
    * `LsEuPlugin.s1Paddr` and `IcachePlugin.lookupPaddr` -- both had one. With `TCR.P=1`
    * the page offset is 13 bits: VA[12] moves from "the PPN's LSB" to "the top bit of the
    * page offset", so `fsPpn(0)` (the raw descriptor's bit 12) is architecturally
    * UNDEFINED and PA[12] must come from the VA.
    *
    * EVERY OTHER TEST IN THIS FILE IS BLIND TO THIS, structurally, for two independent
    * reasons: none of them sets `TCR.P`, and all of them translate through a match-all
    * TTR, whose response PPN is the request's own VPN -- so both the old and the new
    * expression collapse to `PA == VA` and no descriptor bit is ever consulted. This test
    * therefore has to build a real 3-level table and POISON the one bit involved.
    *
    * The construction, chosen so old and new differ by exactly one address bit:
    *   - A7 = 0x0002E100, so the 52-byte frame occupies 0x0002E0CC..0x0002E0FF -- one
    *     8 KiB page (0x0002E000..0x0002FFFF), and every frame byte has VA[12] = 0.
    *   - The leaf descriptor's address field is 0x0002F000: the true 8 KiB page base
    *     0x0002E000 with bit 12 deliberately set to 1, i.e. disagreeing with VA[12].
    *   - PDT=01 resident, CM=00 writethrough, U=M=0.
    * so
    *   correct (PA[12] from the VA)          -> 0x0002E0CC
    *   the bug (PA[12] from the descriptor)  -> 0x0002F0CC
    * and the assertion checks BOTH: the frame is at the right address AND the wrong
    * address is untouched. Verified to FAIL on the pre-fix RTL (frame at 0x0002F0CC,
    * 0x0002E0CC all zero) and pass after. */
  test("FSAVE with TCR.P=1 takes PA[12] from the VA, not the page descriptor",
       VerilatorTest) {
    val Root = 0x00080000L; val Ptr = 0x00081000L; val Leaf = 0x00082000L
    val A7   = 0x0002E100L
    val Good = A7 - 52          // 0x0002E0CC -- PA[12] taken from VA[12] = 0
    val Bad  = Good + 0x1000L   // 0x0002F0CC -- PA[12] taken from the descriptor's bit 12
    val src80 = (BigInt(0x4001) << 64) | BigInt(0x5555)
    val image = ProgramAssembler.assemble(
      f"lea 0x$A7%08x,%%a7 ; $fsaveA7 ; done: bra.s done", loadAddr)
      .getOrElse(fail("assemble failed"))
    var got = 0
    var wrong = 0
    var a7 = 0L
    compiled.doSim(simName("fsave-8k-pa12"), 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val zmem = new m68k040.sim.ConstFillSparseMemory(0.toByte)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd, sharedMem = zmem)
      def pokeLong(addr: Long, v: Long): Unit =
        for (i <- 0 until 4) dmem.pokeByte(addr + i, ((v >> (8 * (3 - i))) & 0xff).toInt)
      // VA 0x0002E0CC: root idx = VA[31:25] = 0, ptr idx = VA[24:18] = 0,
      // leaf idx (8 KiB) = VA[17:13] = 23. UDT=10 resident on the two table levels.
      pokeLong(Root, Ptr | 0x2L)
      pokeLong(Ptr, Leaf | 0x2L)
      pokeLong(Leaf + 23 * 4, 0x0002F000L | 0x1L)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.pageSize8K #= false
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
      // Instructions transparently translated (the code image is elsewhere and is not
      // what is under test); DATA gets NO TTR, so the frame store runs a real walk.
      dut.ctrl.logic.itt0 #= IttMatchAllWt
      dut.ctrl.logic.dtt0 #= 0
      dut.ctrl.logic.srp #= Root; dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.pageSize8K #= true
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
      cd.waitSampling(6000)
      a7 = dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL
      got   = (dmem.peekByte(Good) << 8) | dmem.peekByte(Good + 1)
      wrong = (dmem.peekByte(Bad) << 8) | dmem.peekByte(Bad + 1)
    }
    assert(a7 == Good, f"sanity: the 52-byte frame was emitted; A7 = 0x$a7%08X")
    assert(got == 0x4130,
      f"the frame header must land at the 8 KiB-correct PA 0x$Good%08X (PA[12] from " +
      f"VA[12]=0); found 0x$got%04X there and 0x$wrong%04X at 0x$Bad%08X, which is the " +
      f"address you get by taking PA[12] from the page descriptor's architecturally " +
      f"undefined bit 12")
    assert(wrong == 0x0000,
      f"nothing may be written at 0x$Bad%08X -- that address only exists if PA[12] came " +
      f"from the descriptor instead of the VA; found 0x$wrong%04X")
  }
}
