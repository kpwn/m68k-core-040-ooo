package m68k040.exception

import m68k040.VerilatorTest
import m68k040.decode.SysKind
import m68k040.execute.FpuControlPlugin
import m68k040.mmu.MmuControlPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{PluginHost, FiberPlugin}
import spinal.core.fiber.Fiber
import org.scalatest.funsuite.AnyFunSuite

/** Task 9b: the GENERALIZED `SysKind.FMOVE_FPCTRL` `S_APPLY` arm.
  *
  * Task 9 shipped this arm for exactly one register at a time (`FMOVE.L Dn,FPcr`), with
  * the single value on `sysVal`. Task 9b widens it to ANY SUBSET of {FPCR, FPSR, FPIAR}
  * in ONE retirement, each register's value coming from the `sysAux` slot named by its
  * POSITION in the register list. The `sysRc` BATCH bit (bit 3) selects between the two.
  *
  * The arm's write direction was ALREADY three independent, non-exclusive `when`s (not a
  * one-hot `elsewhen` chain), so this is a value-source change, not a restructuring — and
  * the first test below is the regression that proves the single-register behaviour is
  * byte-identical, not merely "still roughly works".
  *
  * Design: docs/superpowers/specs/2026-08-16-fp-control-multiword-transfer-design.md
  * (Decision 2). Register order: M68000PRM p. 5-91, Divergence Register D9.
  */
class FmovemCtrlApplySpec extends AnyFunSuite {
  private val NEXT_PC = 0x00001040L

  class Dut extends Component {
    val host = new PluginHost
    val fpu  = new FpuControlPlugin()
    val mmuP = new MmuControlPlugin()
    host.asHostOf(Seq[FiberPlugin](fpu, mmuP))

    val sysTriggerIn = in Bool ()
    val sysRcIn      = in UInt (12 bits)
    val sysValIn     = in Bits (32 bits)
    val sysReadDirIn = in Bool ()
    val sysAuxIn     = in Vec (Bits(32 bits), 3)
    val fpccInIn     = in Bits (4 bits)

    val fpcrOut  = out UInt (32 bits)
    val fpsrOut  = out UInt (32 bits)
    val fpiarOut = out UInt (32 bits)
    val fpccWrV  = out Bool ()
    val fpccWrD  = out Bits (4 bits)
    val excActive = out Bool ()

    // Built inside a Fiber so the plugins' `logic` Areas have elaborated first (the
    // ExceptionUnit constructor reads fpuCtrl.fpcr/.fpsr/.fpiar eagerly and `require`s it).
    Fiber build new Area {
      val exc = new ExceptionUnit(
        ss = new SystemState, mmuCtrl = mmuP, fpuCtrlOpt = fpu,
        entryTrigger = False, entryVector = U(0, 8 bits), entryPc = U(0, 32 bits),
        rteTrigger = False, rtePc = U(0, 32 bits),
        committedCcr = U(0, 5 bits),
        sysTrigger = sysTriggerIn,
        sysKind    = SysKind.FMOVE_FPCTRL.asBits.asUInt.resize(4),
        sysReadDir = sysReadDirIn,
        sysVal     = sysValIn,
        sysAux     = sysAuxIn,
        sysRc      = sysRcIn,
        sysPc      = U(NEXT_PC - 4, 32 bits),
        sysNextPc  = U(NEXT_PC, 32 bits))
      exc.sqDrained := True; exc.dcQuiesced := True; exc.maintDoneIn := True
      exc.committedFpccIn := fpccInIn
      // Sticky capture: fpccWrite fires for exactly one cycle inside S_APPLY.
      val wrV = RegInit(False); val wrD = Reg(Bits(4 bits)) init 0
      when(exc.fpccWriteValid) { wrV := True; wrD := exc.fpccWriteData }
      when(sysTriggerIn)       { wrV := False }
      fpccWrV := wrV; fpccWrD := wrD
      excActive := exc.active
      fpcrOut  := fpu.logic.fpcr
      fpsrOut  := fpu.logic.fpsr
      fpiarOut := fpu.logic.fpiar
    }
  }

  private lazy val compiled = SimConfig.withVerilator.compile(new Dut)

  private def init(dut: Dut): Unit = {
    dut.sysTriggerIn #= false; dut.sysRcIn #= 0; dut.sysValIn #= 0
    dut.sysReadDirIn #= false; dut.fpccInIn #= 0
    (0 until 3).foreach(i => dut.sysAuxIn(i) #= 0)
    dut.clockDomain.waitSampling(4)
  }

  /** Pulse one write-direction FMOVE(M)_FPCTRL through IDLE -> S_APPLY -> S_REDIR. */
  private def apply(dut: Dut, rc: Int, sysVal: Long, aux: Seq[Long]): Unit = {
    dut.sysRcIn #= rc
    dut.sysValIn #= BigInt(sysVal)
    aux.zipWithIndex.foreach { case (v, i) => dut.sysAuxIn(i) #= BigInt(v) }
    dut.sysTriggerIn #= true
    dut.clockDomain.waitSampling()
    dut.sysTriggerIn #= false
    dut.clockDomain.waitSampling(8)
    assert(!dut.excActive.toBoolean, "FSM did not return to IDLE")
  }

  // ── Regression: the single-register (batch = 0) path is byte-identical ────────────

  test("batch=0 (Task 9's single-register form) still takes its value from sysVal", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      // A deliberately DIFFERENT value in every sysAux slot: if the generalized arm leaked
      // the batch source into the single-register path, these would land instead.
      val poison = Seq(0xDEAD0000L, 0xDEAD1111L, 0xDEAD2222L)
      apply(dut, rc = 0x4, sysVal = 0x00000030L, aux = poison)   // mask 100 = FPCR
      assert(dut.fpcrOut.toBigInt == 0x30, s"FPCR=${dut.fpcrOut.toBigInt.toString(16)}")
      assert(dut.fpsrOut.toBigInt == 0 && dut.fpiarOut.toBigInt == 0, "only FPCR may change")
      apply(dut, rc = 0x1, sysVal = 0x0000ABCDL, aux = poison)   // mask 001 = FPIAR
      assert(dut.fpiarOut.toBigInt == 0xABCD, s"FPIAR=${dut.fpiarOut.toBigInt.toString(16)}")
      assert(dut.fpcrOut.toBigInt == 0x30, "FPCR must be undisturbed")
      apply(dut, rc = 0x2, sysVal = 0x0A00F080L, aux = poison)   // mask 010 = FPSR
      // FpuControlPlugin masks FPCC [27:24] off itself; the nibble goes to the FPCC PRF.
      assert(dut.fpsrOut.toBigInt == 0x0000F080L, s"FPSR=${dut.fpsrOut.toBigInt.toString(16)}")
      assert(dut.fpccWrV.toBoolean, "an FPSR write must always write the renamed FPCC too")
      // arch {N,Z,I,NaN} = 0xA -> internal {NaN,I,Z,N} = 0x5
      assert(dut.fpccWrD.toBigInt == 0x5, s"FPCC=${dut.fpccWrD.toBigInt.toString(16)}")
    }
  }

  // ── The genuinely new case: any subset, one retirement ────────────────────────────

  test("batch=1 mask=111: all three registers land in ONE retirement, from sysAux[0..2]", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      // Position order is FPCR, FPSR, FPIAR (D9) -> aux0/aux1/aux2 respectively.
      // sysVal is poisoned: in batch mode NOTHING may come from it.
      apply(dut, rc = 0x8 | 0x7, sysVal = 0xBADBAD00L,
            aux = Seq(0x00000030L, 0x0400F080L, 0x0000ABCDL))
      assert(dut.fpcrOut.toBigInt  == 0x30,     s"FPCR=${dut.fpcrOut.toBigInt.toString(16)}")
      assert(dut.fpsrOut.toBigInt  == 0xF080,   s"FPSR=${dut.fpsrOut.toBigInt.toString(16)}")
      assert(dut.fpiarOut.toBigInt == 0xABCD,   s"FPIAR=${dut.fpiarOut.toBigInt.toString(16)}")
      // arch {N,Z,I,NaN} = 0x4 -> internal 0x2
      assert(dut.fpccWrV.toBoolean && dut.fpccWrD.toBigInt == 0x2,
             s"FPCC=${dut.fpccWrD.toBigInt.toString(16)}")
    }
  }

  test("batch=1 mask=110 {FPCR,FPSR}: FPSR takes POSITION 1, and FPIAR is untouched", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      apply(dut, rc = 0x8 | 0x1, sysVal = 0, aux = Seq(0x11111111L, 0, 0))  // seed FPIAR
      assert(dut.fpiarOut.toBigInt == 0x11111111L)
      apply(dut, rc = 0x8 | 0x6, sysVal = 0xBADBAD00L,
            aux = Seq(0x00000020L, 0x00001080L, 0xDEADBEEFL))
      assert(dut.fpcrOut.toBigInt == 0x20, s"FPCR=${dut.fpcrOut.toBigInt.toString(16)}")
      assert(dut.fpsrOut.toBigInt == 0x1080,
             s"FPSR must come from aux[1], not aux[2]: ${dut.fpsrOut.toBigInt.toString(16)}")
      assert(dut.fpiarOut.toBigInt == 0x11111111L, "an unselected register must not change")
    }
  }

  test("batch=1 mask=011 {FPSR,FPIAR}: FPSR shifts to POSITION 0, FPIAR to 1", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      apply(dut, rc = 0x8 | 0x4, sysVal = 0, aux = Seq(0x00000040L, 0, 0))  // seed FPCR
      apply(dut, rc = 0x8 | 0x3, sysVal = 0xBADBAD00L,
            aux = Seq(0x00002080L, 0x00005678L, 0xDEADBEEFL))
      assert(dut.fpsrOut.toBigInt  == 0x2080, s"FPSR=${dut.fpsrOut.toBigInt.toString(16)}")
      assert(dut.fpiarOut.toBigInt == 0x5678, s"FPIAR=${dut.fpiarOut.toBigInt.toString(16)}")
      assert(dut.fpcrOut.toBigInt  == 0x40, "an unselected register must not change")
    }
  }

  test("batch=1 mask=101 {FPCR,FPIAR}: FPIAR takes POSITION 1 (FPSR is skipped)", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      apply(dut, rc = 0x8 | 0x2, sysVal = 0, aux = Seq(0x00003080L, 0, 0))  // seed FPSR
      apply(dut, rc = 0x8 | 0x5, sysVal = 0xBADBAD00L,
            aux = Seq(0x00000050L, 0x00009ABCL, 0xDEADBEEFL))
      assert(dut.fpcrOut.toBigInt  == 0x50,   s"FPCR=${dut.fpcrOut.toBigInt.toString(16)}")
      assert(dut.fpiarOut.toBigInt == 0x9ABC,
             s"FPIAR must come from aux[1], not aux[2]: ${dut.fpiarOut.toBigInt.toString(16)}")
      assert(dut.fpsrOut.toBigInt  == 0x3080, "an unselected register must not change")
    }
  }

  test("batch=1 with a single-bit mask behaves exactly like the popcount-1 program", VerilatorTest) {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10); init(dut)
      // popcount 1 is folded into this task's scope; its one value is at POSITION 0.
      apply(dut, rc = 0x8 | 0x4, sysVal = 0xBADBAD00L, aux = Seq(0x00000010L, 0xDEAD1111L, 0xDEAD2222L))
      assert(dut.fpcrOut.toBigInt == 0x10, s"FPCR=${dut.fpcrOut.toBigInt.toString(16)}")
      assert(dut.fpsrOut.toBigInt == 0 && dut.fpiarOut.toBigInt == 0)
    }
  }
}
