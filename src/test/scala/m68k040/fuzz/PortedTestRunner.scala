package m68k040.fuzz

import m68k040.M68kSim
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._

/** Outcome of running one m68k-ooo-ported directed asm test. */
sealed trait PortedOutcome
case object PortedPass extends PortedOutcome
final case class PortedFail(word: Long) extends PortedOutcome
final case class PortedHang(cycles: Long) extends PortedOutcome
final case class PortedGenFail(reason: String) extends PortedOutcome

/** Runs m68k-ooo's self-checking directed asm tests against this core.
  *
  * Each test is a standalone program that writes a sentinel word to
  * 0xFFFF0000 before halting: 0xC0FFEE00 = PASS, anything else = FAIL, no
  * write before the timeout = HANG. This mirrors m68k-ooo's own C++
  * testbench (tb/tb_top.cpp) detection exactly -- see
  * docs/superpowers/specs/2026-07-16-port-m68kooo-asm-tests-design.md.
  *
  * JVM discipline: ONE Verilator compile per JVM (lazy, shared across every
  * test), mirroring FuzzRunner.compiled -- never re-compile per test.
  */
object PortedTestRunner {
  val loadAddr: Long = ProgramAssembler.DefaultLoadAddress
  val SentinelAddr: Long = 0xFFFF0000L
  val PassWord: Long = 0xC0FFEE00L

  lazy val compiled = M68kSim().withVerilator.compile(new FuzzCoreDut)
  private var runIdx = 0

  def run(name: String, src: String, timeoutCycles: Long, simSeed: Int = 1): PortedOutcome = {
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return PortedGenFail(s"assemble: ${err.reason}")
    }

    runIdx += 1
    var outcome: PortedOutcome = PortedHang(0)
    compiled.doSim(s"ported_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      FuzzDut.attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)

      // Also seed the D-SIDE view of the program image (ported-tests triage, cluster 2
      // / PC-relative indexed): `FuzzDut.attachProgram` above writes the I-cache's own
      // private SparseMemory with a per-16-bit-word BYTE-SWAPPED layout (its own
      // established, Axi4ReadOnlySlaveAgent-specific convention -- NOT a plain
      // byte-at-address mapping; do not "fix" it, every existing instruction-fetch test
      // depends on it exactly as-is). The D-cache's `BehavioralMemAgent` (`dmem`) is a
      // SEPARATE, independently-random-filled SparseMemory using the ordinary plain
      // byte-at-address convention (the same one every passing store/load ported test
      // already relies on). A PC-relative data read of a literal/table value embedded
      // in the code region (e.g. `move.b (d8,PC,Xn),Dn` reading a ROM-style jump/data
      // table right after the opcode) goes through the D-side pipeline, so it previously
      // saw pure random fill instead of the real program bytes even though the AGU/
      // decode/cache path all resolved the exact correct address. Write the SAME
      // `image.bytes` into `dmem.mem` using dmem's OWN plain convention (no swap) so a
      // D-side literal-pool read observes the identical bytes the I-cache fetched as
      // code, matching how a real 68040's unified physical memory would behave.
      for (i <- image.bytes.indices) dmem.mem.write(loadAddr + i, image.bytes(i).toByte)

      // The sentinel word must start at a KNOWN value, not SparseMemory's
      // random fill for never-written bytes (same reasoning as the sandbox
      // pre-fill in FuzzLockStepSpec.scala's task #143 fix) -- otherwise a
      // random nonzero value there could be misread as an immediate (wrong)
      // sentinel write before the program has even started.
      for (i <- 0 until 4) dmem.mem.write(SentinelAddr + i, 0.toByte)

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
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot: supervisor (SR 0x2700), SSP/ISP 0x00100000, USP 0 -- identical
      // to FuzzRunner.run's boot sequence (FuzzLockStepSpec.scala).
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

      // debug-only, env-gated trace for the MI_MOVE_EAEA_REV (memory-indirect dst,
      // plain-memory src) MOVE crack -- ported-tests triage (move_l_abs_memind_dst).
      // Zero cost unless PORTED_TRACE_MI is set. Mirrors FuzzLockStepSpec.scala's
      // FUZZ_TRACE_CPLX ucBegin trace pattern.
      if (sys.env.contains("PORTED_TRACE_MI")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dec.logic.ucBegin.toBoolean) {
            val entryPc  = dut.dec.logic.ucEntryPkt.pc.toLong & 0xffffffffL
            val realEntry = dut.dec.logic.ucRealEntry.toInt
            val isMemInd = dut.dec.logic.ucIsMemInd.toBoolean
            val moveDstMi = dut.dec.logic.ucMoveDstMi.toBoolean
            val moveSrcMi = dut.dec.logic.ucMoveSrcMi.toBoolean
            val dstEaEa  = dut.dec.logic.ucMoveDstMiEaEa.toBoolean
            val srcEaEa  = dut.dec.logic.ucMoveSrcMiEaEa.toBoolean
            val eaBase   = dut.dec.logic.ucEntryCtx.eaBase.toInt
            val eaBaseV  = dut.dec.logic.ucEntryCtx.eaBaseValid.toBoolean
            val eaDisp   = dut.dec.logic.ucEntryCtx.eaDispLo.toLong & 0xffffffffL
            val miOd     = dut.dec.logic.ucEntryCtx.miOd.toLong & 0xffffffffL
            val miPost   = dut.dec.logic.ucEntryCtx.miPost.toBoolean
            val otherBase  = dut.dec.logic.ucEntryCtx.miOtherEaBase.toInt
            val otherBaseV = dut.dec.logic.ucEntryCtx.miOtherEaBaseValid.toBoolean
            val otherDisp  = dut.dec.logic.ucEntryCtx.miOtherEaDispLo.toLong & 0xffffffffL
            println(f"[mitrace] UC-BEGIN cyc=$trCyc%5d entryPc=0x$entryPc%08x realEntry=$realEntry isMemInd=$isMemInd " +
              f"moveDstMi=$moveDstMi moveSrcMi=$moveSrcMi dstEaEa=$dstEaEa srcEaEa=$srcEaEa " +
              f"eaBase=$eaBase eaBaseV=$eaBaseV eaDisp=0x$eaDisp%08x miOd=0x$miOd%08x miPost=$miPost " +
              f"otherBase=$otherBase otherBaseV=$otherBaseV otherDisp=0x$otherDisp%08x")
          }
        }
      }

      // debug-only, env-gated trace for the DIVU.L/DIVS.L 32/32 crack (DIV + trailing
      // DIVREM) -- ported-tests triage (divl_basic HANG). Prints every ROB commit +
      // every DivEu writeback, so a stall shows up as "commits stop advancing" with no
      // further DIV-WB, pinning whether the divider itself never completes or a
      // downstream resource (scoreboard/ROB slot) never frees. Zero cost unless
      // PORTED_TRACE_DIV is set.
      if (sys.env.contains("PORTED_TRACE_DIV")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          for (k <- 0 until 2) {
            val c = dut.rob.logic.commitObs(k)
            if (c.fire.toBoolean) {
              println(f"[divtrace] COMMIT cyc=$trCyc%6d port=$k robId=${c.robId.toInt} pc=0x${c.pc.toLong & 0xffffffffL}%08x")
            }
          }
          if (dut.divEu.logic.wbObs.valid.toBoolean) {
            val w = dut.divEu.logic.wbObs
            println(f"[divtrace] DIV-WB cyc=$trCyc%6d rid=${w.robId.toInt} dstArch=${w.dstArch.toInt} result=0x${w.result.toLong & 0xffffffffL}%08x nzvc=0x${w.nzvc.toInt & 0xf}%x " +
              f"nzvcWrite=${w.nzvcWrite.toBoolean} iqCplxNzvcWakeupValid=${dut.iq.cplxNzvcWakeupPort.valid.toBoolean} " +
              f"iqCplxNzvcWakeupPayload=${dut.iq.cplxNzvcWakeupPort.payload.toInt}")
          }
        }
      }

      // debug-only, env-gated trace for D-cache load commands (task #169, ported-tests
      // triage, btst_pcrel_src investigation). Zero cost unless PORTED_TRACE_DLOAD is set.
      if (sys.env.contains("PORTED_TRACE_DLOAD")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean) {
            println(f"[dload] cyc=$trCyc%6d CMD vaddr=0x${dut.dcache.logic.loadCmdPort.payload.vaddr.toLong & 0xffffffffL}%08x")
          }
          if (dut.dcache.logic.loadRspPort.valid.toBoolean) {
            println(f"[dload] cyc=$trCyc%6d RSP data=0x${dut.dcache.logic.loadRspPort.payload.data.toLong & 0xffffffffL}%08x")
          }
        }
      }

      // debug-only, env-gated trace for the CPLX-NZVC dynamic wakeup (task #167,
      // ported-tests triage cluster 9 mull_basic HANG investigation). Prints the
      // wakeup port + any slot with cplxNzvcWait latched, so a permanently-latched
      // wait bit (never cleared by a matching wakeup) shows up directly. Zero cost
      // unless PORTED_TRACE_IQNZVC is set.
      if (sys.env.contains("PORTED_TRACE_IQNZVC")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.iq.cplxNzvcWakeupPort.valid.toBoolean) {
            println(f"[iqnzvc] cyc=$trCyc%6d WAKEUP payload=${dut.iq.cplxNzvcWakeupPort.payload.toInt}")
          }
          val busyHex = dut.iq.logic.cplxNzvcBusy.toBigInt.toString(16)
          if (dut.iq.logic.cplxNzvcBusy.toBigInt != 0) {
            println(f"[iqnzvc] cyc=$trCyc%6d cplxNzvcBusy=0x$busyHex")
          }
          val waiting = dut.iq.logic.slots.zipWithIndex.filter { case (s, _) => s.sel.toBoolean && s.cplxNzvcWait.toBoolean }
          waiting.foreach { case (s, i) =>
            println(f"[iqnzvc] cyc=$trCyc%6d slot=$i%2d WAIT robId=${s.context.robId.toInt} " +
              f"readsNzvc=${s.context.uop.readsNzvc.toBoolean} pNzvcSrc=${s.context.uop.pNzvcSrc.toInt}")
          }
        }
      }

      // debug-only, env-gated trace for the exception-frame odd-SP D-cache push
      // (ported-tests triage, exc_aline_odd_sp_mmu_dcache) -- prints every dcStore
      // command the exception FSM issues (paddr/size/data) so a cross-cache-line
      // frame-word push can be inspected directly. Zero cost unless PORTED_TRACE_EXC
      // is set.
      if (sys.env.contains("PORTED_TRACE_EXC")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.rob.logic.exc.dcStore.valid.toBoolean) {
            val p = dut.rob.logic.exc.dcStore.payload
            println(f"[exctrace] DCSTORE cyc=$trCyc%6d paddr=0x${p.paddr.toLong & 0xffffffffL}%08x " +
              f"size=${p.size.toEnum} data=0x${p.data.toLong & 0xffffffffL}%08x useStrb=${p.useStrb.toBoolean} " +
              f"strb=0x${p.strb.toLong & 0xffffL}%04x")
          }
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean && dut.dcache.logic.loadCmdPort.ready.toBoolean) {
            val c = dut.dcache.logic.loadCmdPort.payload
            println(f"[exctrace] LOADCMD cyc=$trCyc%6d vaddr=0x${c.vaddr.toLong & 0xffffffffL}%08x " +
              f"paddr=0x${c.paddr.toLong & 0xffffffffL}%08x size=${c.size.toEnum}")
          }
          if (dut.dcache.logic.loadRspPort.valid.toBoolean) {
            val r = dut.dcache.logic.loadRspPort.payload
            println(f"[exctrace] LOADRSP cyc=$trCyc%6d data=0x${r.data.toLong & 0xffffffffL}%08x fault=${r.fault.toBoolean}")
          }
          if (dut.rob.logic.exc.redirectValid.toBoolean) {
            println(f"[exctrace] EXCREDIRECT cyc=$trCyc%6d pc=0x${dut.rob.logic.exc.redirectPc.toLong & 0xffffffffL}%08x")
          }
        }
      }

      var cyc = 0L
      var word = 0L
      while (word == 0 && cyc < timeoutCycles) {
        cd.waitSampling()
        cyc += 1
        val b0 = dmem.mem.read(SentinelAddr).toLong & 0xffL
        val b1 = dmem.mem.read(SentinelAddr + 1).toLong & 0xffL
        val b2 = dmem.mem.read(SentinelAddr + 2).toLong & 0xffL
        val b3 = dmem.mem.read(SentinelAddr + 3).toLong & 0xffL
        word = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
      }
      if (sys.env.contains("PORTED_TRACE_EXC")) {
        for (a <- 0xFFF0L to 0x10010L) {
          val v = dmem.mem.read(a).toLong & 0xffL
          println(f"[exctrace] MEM 0x$a%08x = 0x$v%02x")
        }
      }
      outcome =
        if (word == 0) PortedHang(cyc)
        else if (word == PassWord) PortedPass
        else PortedFail(word)
    }
    outcome
  }
}
