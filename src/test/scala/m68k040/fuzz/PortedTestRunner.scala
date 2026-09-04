package m68k040.fuzz

import m68k040.M68kSim
import m68k040.oracle.ProgramAssembler
import spinal.core.sim._

/** Cache posture for ported-test execution. */
sealed trait CachePosture
object CachePosture {
  /** Run exactly as the test source configures CACR/MMU/DTT itself (today's only
    * behavior). */
  case object AsWritten extends CachePosture
  /** Harness-injected prologue: poke CACR.DE=1 + a cacheable identity/DTT mapping
    * over the test's working set before execution starts (Slice P6's §6.1 sweep). */
  case object ForceCacheableCopyback extends CachePosture

  // The already-proven transparent-translation partition used by the D-cache
  // performance programs. A set mask bit is "don't care", so 0x7F covers a
  // full 2 GiB half rather than 128 MiB. Keeping the high half inhibited is
  // essential: the harness observes completion through an AXI-visible store to
  // 0xFFFF0000, which a copyback mapping could retain only in cache.
  private[fuzz] val LowHalfCopybackTtr: BigInt = BigInt("007FE020", 16)
  private[fuzz] val HighHalfInhibitedTtr: BigInt = BigInt("807FE060", 16)
  private[fuzz] val CacrDataAndInstructionEnable: BigInt = BigInt("80008000", 16)
}

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

  def run(name: String, src: String, timeoutCycles: Long, simSeed: Int = 1,
        cachePosture: CachePosture = CachePosture.AsWritten): PortedOutcome = {
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => return PortedGenFail(s"assemble: ${err.reason}")
    }

    // Harness-side "clean debug halt" detection for BKPT (task #178 cluster12,
    // exc_bkpt_decode.s): m68k-ooo's own C++ testbench (tb/tb_top.cpp) watches a
    // `dbg_break_uop_fire`-style signal and injects the PASS sentinel itself --
    // a BKPT test's guest program is, by construction, never expected to reach
    // a sentinel write (see that test's own header comment). We reconstruct the
    // equivalent from the assembled image: scan for any 16-bit word in the BKPT
    // range 0x4848-0x484F and watch the ROB's commit-observation ports for that
    // exact PC actually retiring -- which only happens if control flow really
    // executes it as an instruction (BKPT decodes as a NOP-shaped commit, see
    // OperationDecoder.scala), not merely if the bit pattern appears as data.
    val bkptPcs: Set[Long] = {
      var pcs = Set.empty[Long]
      var i = 0
      while (i + 1 < image.bytes.length) {
        val w = ((image.bytes(i).toLong & 0xffL) << 8) | (image.bytes(i + 1).toLong & 0xffL)
        if ((w & 0xfff8L) == 0x4848L) pcs += (loadAddr + i)
        i += 2
      }
      pcs
    }

    runIdx += 1
    var outcome: PortedOutcome = PortedHang(0)
    compiled.doSim(s"ported_$runIdx", simSeed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)

      // Task #211: use the DECERR-capable read agent (mirrors `dmem` below) so an
      // instruction fetch to genuinely-unmapped space (e.g. exc_ifetch_bus_error.s's
      // 0xAAAA0000 target) gets a real AXI bus error instead of a silently-successful
      // zero-filled read — IcachePlugin's REFILL resp-check (task #211) can only ever
      // be exercised by the ported-test corpus if this harness can actually produce
      // one. Population convention is byte-identical to the old `attachProgram`, so
      // every other (mapped) fetch is unaffected.
      val iAgent = FuzzDut.attachProgramWithBusErrors(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      // Task #189: inject a real DECERR for the D-side data bus on a genuinely
      // undecoded physical address (mirrors the real SoC's axi_xbar decode the
      // ported-test corpus's exc_bus_error* headers describe — see
      // BehavioralMem.decoded's doc comment). Opt-in ONLY for this (data) agent —
      // the ITLB/DTLB table-walker memories below are untouched (a separate MMU
      // concern, out of this task's scope; changing their behavior risked
      // regressing the whole MMU test cluster for no benefit here).
      //
      // `sharedMem` is a `ConstFillSparseMemory(0xFF)` rather than the stock
      // `SparseMemory()`: the vendored m68k-ooo corpus was written against a testbench
      // that "initialises all unmapped RAM bytes to 0xFF" (verbatim from
      // `cinv_line_basic.s`'s header), and cinv_line_basic / mmu_ttr_cm_copyback_vs_
      // serialized compare a deliberately never-written address against a hardcoded
      // 0xFFFFFFFF to prove a dirty line really was DROPPED rather than written back.
      // SpinalHDL's stock SparseMemory fills a freshly-allocated 1 MiB chunk with
      // deterministic pseudo-random bytes instead (see ConstFillSparseMemory's doc
      // comment for the decompiled mechanism), which those tests can never satisfy.
      // Scoped deliberately to the ported-test D side: no other harness's memory model
      // changes, and the I-side agent above keeps SpinalHDL's stock random fill (which
      // several instruction-fetch tests rely on to make beyond-program bytes decode as
      // garbage rather than as a uniform, executable 0xFF... stream).
      //
      // KNOWN, INVESTIGATED CONSEQUENCE -- `rom_scc_mmio_btst_dbf_timeout`. That test
      // polls `btst #0,(0x50f0c022)`, a Q700 SCC status mirror. `AxiMemModel.decoded`
      // classifies top-nibble 0x5 as decoded, so the poll does NOT bus-fault here and
      // simply reads the memory model's fill byte. Under the stock pseudo-random fill
      // the byte at that one address happened to have bit 0 CLEAR, so the DBF loop ran
      // its full 256 iterations and the test went green -- a coincidence of a single
      // random byte, not evidence of anything. Under the corpus's real 0xFF convention
      // the bit reads SET, the loop exits on iteration 1, and the test reports its own
      // FAIL_MISMATCH (0xBADC0C22) diagnostic. Per its own header this test cannot
      // legitimately pass in a harness with no SCC model backing 0x50f0c022 (the
      // original m68k-ooo tb leaves that address unmapped, where it instead reports
      // FAIL_NO_SCC) -- so it is left red rather than special-cased here. Backing the
      // SCC window is a deliberate, separate decision, not a fill-convention fix.
      //
      // Review note (harness-SMC-fix, post-commit): passing this non-null `sharedMem`
      // means `AxiMemModel` no longer draws a `simRandom.nextLong()` seed for a fresh
      // `SparseMemory()` here, shifting the shared PRNG stream every later
      // `StreamReadyRandomizer` (ar/aw/w backpressure timing) consumes from for the
      // REST of the sim -- every ported test's AXI-ready timing is re-rolled relative
      // to pre-fix runs. Independently verified this does NOT explain any of the
      // observed pass/fail deltas (a from-scratch isolation experiment holding the
      // fill fixed while restoring the old stream position reproduced the exact same
      // 13-fixed/1-new attribution) -- but if a FUTURE investigation on this branch
      // sees an unexplained ported-test outcome shift with no corresponding RTL or
      // harness diff, check here first before chasing a phantom RTL race (this project
      // has been burned by exactly this PRNG-stream-position artifact class before,
      // see the V1.6b MSHR investigation).
      val dsideMem = new m68k040.sim.ConstFillSparseMemory(0xff.toByte)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd,
                                                   sharedMem = dsideMem, injectBusErrors = true)
      // SELF-MODIFYING CODE: mirror every runtime D-side store byte into the I-side's
      // SEPARATE program image. The I and D views are two different `SparseMemory`
      // objects holding the same architectural byte-at-address image. Seeding both at
      // setup (below) is enough for ordinary tests, but
      // a RUNTIME store -- an SMC test patching its own code, or a test staging code in
      // RAM and jumping to it -- only ever landed in the D-side memory, so instruction
      // fetch could never observe it no matter how correct the RTL's D-cache/I-cache/
      // CPUSH/CINV coherency handling was.
      //
      // Both memories now use the same address convention, so runtime stores mirror
      // directly by byte address. This also makes staged code and self-modifying code
      // match real 68k memory instead of relying on a harness-only word swap.
      //
      // Only `dmem` gets the observer. The two MMU table-walker agents below SHARE
      // `dmem.mem` but have their own independent write engines, and the only thing they
      // ever write is a U/M-bit descriptor update into a page table the test built in a
      // data region -- never code. Mirroring those would add a way to corrupt the I-side
      // image for no benefit, so they are deliberately left unhooked.
      dmem.setByteWriteObserver((addr, byte) => iAgent.mem.write(addr, byte))
      // Task #194: SHARE dmem's backing SparseMemory with both MMU table-walker AXI
      // ports. Architecturally the page table lives in ordinary RAM — a directed
      // ported test builds it with REAL `move.l #imm,addr` instructions through the
      // D-cache (exactly like real 68040 boot code), so the walker's independent AXI
      // read port must observe the SAME memory image, not its own private
      // random-filled one (which is what every one of the 9 MMU-cluster ported tests
      // was actually hitting before this fix — the walker read garbage descriptors
      // off an unrelated SparseMemory and spuriously page-faulted regardless of RTL
      // correctness). `BehavioralMemAgent`'s `sharedMem` param already existed for
      // exactly this (see its doc comment) — `ExecuteLockStepSpec`'s MMU tests never
      // needed it because they poke the walker's memory directly via a whitebox
      // `buildMmuTable` helper instead of running real architected store instructions.
      // (The two table-walker AXI memories that used to be attached here are gone:
      // the ITLB/DTLB walkers no longer emit AXI. Their descriptor reads and U/M
      // writebacks are DcacheService client traffic now, so they reach memory
      // through the D-cache above -- which is also the point: a page-table line
      // sitting dirty in L1D is now visible to the walk.)

      // Also seed the D-SIDE view of the program image (ported-tests triage, cluster 2
      // / PC-relative indexed): `FuzzDut.attachProgram` above writes the I-cache's own
      // private SparseMemory with a per-16-bit-word BYTE-SWAPPED layout (its own
      // established instruction-fetch convention -- NOT a plain
      // byte-at-address mapping; do not "fix" it, every existing instruction-fetch test
      // depends on it exactly as-is). The D-cache's `BehavioralMemAgent` (`dmem`) is a
      // SEPARATE SparseMemory (0xFF-filled, see above) using the ordinary plain
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

      // The sentinel word must start at ZERO, not at the D-side memory's fill value
      // for never-written bytes (same reasoning as the sandbox pre-fill in
      // FuzzLockStepSpec.scala's task #143 fix) -- otherwise a nonzero value there
      // would be misread as an immediate (wrong) sentinel write before the program
      // has even started. This is MANDATORY, not merely defensive: the sentinel poll
      // below treats `word == 0` as "not written yet", and the 0xFF fill would
      // otherwise read back as an instant 0xFFFFFFFF "FAIL" on cycle 1.
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

      cachePosture match {
        case CachePosture.AsWritten =>
          // Preserve the existing boot posture exactly. In particular, do not
          // add a cycle or write any architectural MMU/cache register here.
          ()
        case CachePosture.ForceCacheableCopyback =>
          // Design §6.1 alternate posture. Cover the low 2 GiB (including the
          // 0x40800000 program and normal RAM working sets) with identity/
          // COPYBACK transparent translations on both instruction and data
          // sides. Cover the high 2 GiB with identity/INHIBITED data translation
          // so MMIO and the AXI-observed sentinel remain visible. Install the
          // complete mapping before TC.E and before the first redirect.
          dut.ctrl.logic.itt0 #= CachePosture.LowHalfCopybackTtr
          dut.ctrl.logic.itt1 #= 0
          dut.ctrl.logic.dtt0 #= CachePosture.LowHalfCopybackTtr
          dut.ctrl.logic.dtt1 #= CachePosture.HighHalfInhibitedTtr
          dut.rob.logic.exc.ss.cacr #= CachePosture.CacrDataAndInstructionEnable
          dut.ctrl.logic.mmuEnable #= true
          cd.waitSampling()

          // Fail closed if the intended second posture silently decays into an
          // AsWritten run because of a stale hierarchy name or non-sticky poke.
          assert(dut.ctrl.logic.mmuEnable.toBoolean,
            "ForceCacheableCopyback did not enable TC.E")
          assert(dut.ctrl.logic.itt0.toBigInt == CachePosture.LowHalfCopybackTtr,
            s"ForceCacheableCopyback ITT0 readback was 0x${dut.ctrl.logic.itt0.toBigInt.toString(16)}")
          assert(dut.ctrl.logic.dtt0.toBigInt == CachePosture.LowHalfCopybackTtr,
            s"ForceCacheableCopyback DTT0 readback was 0x${dut.ctrl.logic.dtt0.toBigInt.toString(16)}")
          assert(dut.ctrl.logic.dtt1.toBigInt == CachePosture.HighHalfInhibitedTtr,
            s"ForceCacheableCopyback DTT1 readback was 0x${dut.ctrl.logic.dtt1.toBigInt.toString(16)}")
          assert(dut.rob.logic.exc.ss.cacr.toBigInt == CachePosture.CacrDataAndInstructionEnable,
            s"ForceCacheableCopyback CACR readback was 0x${dut.rob.logic.exc.ss.cacr.toBigInt.toString(16)}")
      }

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
            val miHostImm   = dut.dec.logic.ucEntryCtx.miHostImm.toLong & 0xffffffffL
            val miOtherIsImm= dut.dec.logic.ucEntryCtx.miOtherIsImm.toBoolean
            val miEntry     = dut.dec.logic.ucMiEntry.toInt
            println(f"[mitrace] UC-BEGIN cyc=$trCyc%5d entryPc=0x$entryPc%08x realEntry=$realEntry isMemInd=$isMemInd " +
              f"moveDstMi=$moveDstMi moveSrcMi=$moveSrcMi dstEaEa=$dstEaEa srcEaEa=$srcEaEa " +
              f"eaBase=$eaBase eaBaseV=$eaBaseV eaDisp=0x$eaDisp%08x miOd=0x$miOd%08x miPost=$miPost " +
              f"otherBase=$otherBase otherBaseV=$otherBaseV otherDisp=0x$otherDisp%08x " +
              f"miHostImm=0x$miHostImm%08x miOtherIsImm=$miOtherIsImm miEntry=$miEntry")
          }
        }
      }

      // debug-only, env-gated trace for the fetch/predecode `fed` packet stream
      // (ported-tests triage cluster 11, move_abs_src_full_memind_dst investigation) --
      // prints every accepted fed group's packet(0) pc/simple/complex/fault/wordCount,
      // so a stall from a mis-framed length (predecode) can be distinguished from a
      // downstream stall (decode/issue/microcode). Zero cost unless PORTED_TRACE_FED
      // is set.
      if (sys.env.contains("PORTED_TRACE_FED")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dec.logic.fed.valid.toBoolean && dut.dec.logic.fed.ready.toBoolean) {
            val p0 = dut.dec.logic.fed.payload.packets(0)
            val slot1V = dut.dec.logic.fed.payload.slot1Valid.toBoolean
            val p1pc = dut.dec.logic.fed.payload.packets(1).pc.toLong & 0xffffffffL
            println(f"[fedtrace] cyc=$trCyc%6d FED pc=0x${p0.pc.toLong & 0xffffffffL}%08x " +
              f"simple=${p0.simple.toBoolean} fault=${p0.fault.toBoolean} " +
              f"wordCount=${p0.wordCount.toInt} lenWords=${p0.lenWords.toInt} " +
              f"w0=0x${p0.words(0).toLong & 0xffffL}%04x w1=0x${p0.words(1).toLong & 0xffffL}%04x " +
              f"slot1Valid=$slot1V" + (if (slot1V) f" p1pc=0x$p1pc%08x" else ""))
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
          if (dut.dcache.logic.storePort.valid.toBoolean) {
            val sp = dut.dcache.logic.storePort.payload
            println(f"[dstore] cyc=$trCyc%6d CMD paddr=0x${sp.paddr.toLong & 0xffffffffL}%08x data=0x${sp.data.toLong & 0xffffffffL}%08x")
          }
          for (k <- 0 until 2) {
            val c = dut.rob.logic.commitObs(k)
            if (c.fire.toBoolean) {
              println(f"[commit] cyc=$trCyc%6d port=$k robId=${c.robId.toInt} pc=0x${c.pc.toLong & 0xffffffffL}%08x")
            }
          }
          if (dut.dec.logic.ucActive.toBoolean) {
            println(f"[ucstate] cyc=$trCyc%6d ucActive=true ucPc=${dut.dec.logic.ucPc.toInt}")
          }
        }
      }

      // debug-only, env-gated trace for the D-cache store RMW hit/miss + load-side
      // hit/miss decisions (task #189 investigation, exc_addr_error_odd_rte data
      // corruption chase). Zero cost unless PORTED_TRACE_DCHIT is set.
      if (sys.env.contains("PORTED_TRACE_DCHIT")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dcache.logic.stS2Valid.toBoolean) {
            val hv = (0 until 4).map(i => if (dut.dcache.logic.stS2HitVec(i).toBoolean) "1" else "0").mkString
            println(f"[dchit] cyc=$trCyc%6d ST-S2 paddr=0x${dut.dcache.logic.stS2Payload.paddr.toLong & 0xffffffffL}%08x " +
              f"size=${dut.dcache.logic.stS2Payload.size.toEnum} hitVec=$hv useStrb=${dut.dcache.logic.stS2Payload.useStrb.toBoolean}")
          }
          if (dut.dcache.logic.ldS1Valid.toBoolean) {
            println(f"[dchit] cyc=$trCyc%6d LD-S1 set=${dut.dcache.logic.ldS1Set.toInt}%3d tag=0x${dut.dcache.logic.ldS1Tag.toLong & 0xfffffL}%06x " +
              f"off=${dut.dcache.logic.ldS1Off.toInt}%2d size=${dut.dcache.logic.ldS1Size.toEnum} hit=${dut.dcache.logic.ldS1Hit.toBoolean} way=${dut.dcache.logic.ldS1HitWay.toInt}")
          }
        }
      }

      // DEBUG (pea-cache-evict-2026-08-19 investigation), temporary: full store-pipe
      // cycle-by-cycle trace (S1 read-launch, S2 hit-detect, S3 array-write, and the
      // raw shared-read-port/write-port drive) -- used to pin the exact same-line
      // back-to-back store RMW race that PORTED_TRACE_DCHIT's S2-only view can't show
      // (it only ever compares S2-vs-S3 on the SAME cycle, which misses a race between
      // one store's S1 read-launch and an EARLIER store's S3 write). Zero cost unless
      // PORTED_TRACE_DCPIPE is set.
      if (sys.env.contains("PORTED_TRACE_DCPIPE")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dcache.logic.stS1Valid.toBoolean) {
            println(f"[dcpipe] cyc=$trCyc%6d S1 set=${dut.dcache.logic.stS1Set.toInt}%3d " +
              f"tag=0x${dut.dcache.logic.stS1Tag.toLong & 0xfffffL}%06x off=${dut.dcache.logic.stS1Off.toInt}%2d " +
              f"rdSet=${dut.dcache.logic.rdSet.toInt}%3d rdEn=${dut.dcache.logic.rdEn.toBoolean}")
          }
          if (dut.dcache.logic.stS2Valid.toBoolean) {
            val hv = (0 until 4).map(i => if (dut.dcache.logic.stS2HitVec(i).toBoolean) "1" else "0").mkString
            println(f"[dcpipe] cyc=$trCyc%6d S2 set=${dut.dcache.logic.stS2Payload.paddr.toLong}%08x " +
              f"hitVec=$hv useS3=${dut.dcache.logic.stS2UsesS3Line.toBoolean}")
          }
          if (dut.dcache.logic.stS3Valid.toBoolean) {
            println(f"[dcpipe] cyc=$trCyc%6d S3 paddr=0x${dut.dcache.logic.stS3Payload.paddr.toLong}%08x " +
              f"way=${dut.dcache.logic.stS3Way.toInt} hit=${dut.dcache.logic.stS3Hit.toBoolean} " +
              f"arrayWrite=${dut.dcache.logic.stS3ArrayWrite.toBoolean} " +
              f"oldLine=0x${dut.dcache.logic.stS3OldLine.toBigInt}%032x " +
              f"mergedLine=0x${dut.dcache.logic.stS3MergedLine.toBigInt}%032x")
          }
          for (w <- 0 until 4) {
            if (dut.dcache.logic.wrEn(w).toBoolean) {
              println(f"[dcpipe] cyc=$trCyc%6d WR way=$w set=${dut.dcache.logic.wrSet(w).toInt}%3d")
            }
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
            println(f"[iqnzvc] cyc=$trCyc%6d slot=$i%2d WAIT robId=${s.hot.robId.toInt} " +
              f"readsNzvc=${s.hot.readsNzvc.toBoolean} pNzvcSrc=${s.hot.pNzvcSrc.toInt}")
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
            println(f"[exctrace] EXCREDIRECT cyc=$trCyc%6d pc=0x${dut.rob.logic.exc.redirectPc.toLong & 0xffffffffL}%08x " +
              f"a7=0x${dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL}%08x s=${dut.rob.logic.exc.ss.s.toBoolean} " +
              f"m=${dut.rob.logic.exc.ss.m.toBoolean} isp=0x${dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL}%08x " +
              f"usp=0x${dut.rob.logic.exc.ss.usp.toLong & 0xffffffffL}%08x")
          }
        }
      }

      // debug-only, env-gated trace for the task #176 RTE-CCR regression (task
      // #176-regression: exc_stack_atomicity_stress / pea_aline_irq_storm /
      // via1_t1_irq_storm): prints every rteRetire (trigger) pulse + the RTE's direct
      // NZVC/X restore writes (exc.rteNzvcWriteValid/rteXWriteValid, now a direct
      // write into whatever physical register nzvcRat/xRat's committed mapping
      // currently names — see ExceptionUnit.scala's rteNzvcWriteValid doc comment)
      // + every redirect, with the SSP/A7 bank state. Zero cost unless
      // PORTED_TRACE_RTECCR is set.
      if (sys.env.contains("PORTED_TRACE_RTECCR")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          val rc = dut.rob.logic
          if (rc.rteRetire.toBoolean) {
            println(f"[rteccr] cyc=$trCyc%6d RTE_RETIRE head=${rc.head.toInt}%3d tail=${rc.tail.toInt}%3d count=${rc.count.toInt}%3d")
          }
          if (dut.rob.logic.exc.rteNzvcWriteValid.toBoolean || dut.rob.logic.exc.rteXWriteValid.toBoolean) {
            println(f"[rteccr] cyc=$trCyc%6d RTE_CCR_RESTORE nzvcData=0x${dut.rob.logic.exc.rteNzvcWriteData.toBigInt.toString(16)} " +
              f"xData=${dut.rob.logic.exc.rteXWriteData.toBoolean}")
          }
          if (dut.rob.logic.exc.redirectValid.toBoolean) {
            println(f"[rteccr] cyc=$trCyc%6d REDIRECT pc=0x${dut.rob.logic.exc.redirectPc.toLong & 0xffffffffL}%08x " +
              f"a7=0x${dut.rob.logic.exc.ss.a7.toLong & 0xffffffffL}%08x isp=0x${dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL}%08x msp=0x${dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL}%08x usp=0x${dut.rob.logic.exc.ss.usp.toLong & 0xffffffffL}%08x")
          }
        }
      }

      // debug-only, env-gated PER-ROBID DATAFLOW trace (added for the Task P5.7
      // regression root-cause, kept because nothing else in this file attributes a
      // VALUE to a producing µop): every ALU-EU and LS-EU writeback with its
      // robId/dstArch/result, every LS-EU XLATE (robId + load/store), every D-cache
      // load/store command, and every commit. Reading a wrong effective address or a
      // wrong stored value straight back to the exact µop that produced the operand
      // is what identified the same-cycle dependent ALU dual-issue (see
      // IssueQueuePlugin's `aluSlowHandoff` comment). Zero cost unless
      // PORTED_TRACE_WBDBG is set.
      if (sys.env.contains("PORTED_TRACE_WBDBG")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          for ((eu, nm) <- Seq((dut.eu0, "eu0"), (dut.eu1, "eu1"))) {
            val o = eu.logic.wbObs
            if (o.valid.toBoolean)
              println(f"[bfdbg] cyc=$trCyc%6d $nm WB robId=${o.robId.toInt}%2d dstArch=${o.dstArch.toInt}%2d " +
                f"intW=${o.intWrite.toBoolean} res=0x${o.result.toLong & 0xffffffffL}%08x")
          }
          val lo = dut.lsEu.logic.wbObs
          if (lo.valid.toBoolean)
            println(f"[bfdbg] cyc=$trCyc%6d lsu WB robId=${lo.robId.toInt}%2d dstArch=${lo.dstArch.toInt}%2d " +
              f"intW=${lo.intWrite.toBoolean} res=0x${lo.result.toLong & 0xffffffffL}%08x")
          if (dut.lsEu.logic.s1Valid.toBoolean && dut.lsEu.logic.dbgIsXlate.toBoolean)
            println(f"[bfdbg] cyc=$trCyc%6d lsu XLATE robId=${dut.lsEu.logic.s1Ctx.robId.toInt}%2d " +
              f"isLoad=${dut.lsEu.logic.isLoad.toBoolean} isStore=${dut.lsEu.logic.isStore.toBoolean}")
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean)
            println(f"[bfdbg] cyc=$trCyc%6d dc LOADCMD vaddr=0x${dut.dcache.logic.loadCmdPort.payload.vaddr.toLong & 0xffffffffL}%08x")
          if (dut.dcache.logic.storePort.valid.toBoolean)
            println(f"[bfdbg] cyc=$trCyc%6d dc STORE paddr=0x${dut.dcache.logic.storePort.payload.paddr.toLong & 0xffffffffL}%08x data=0x${dut.dcache.logic.storePort.payload.data.toLong & 0xffffffffL}%08x")
          for (k <- 0 until 2) {
            val c = dut.rob.logic.commitObs(k)
            if (c.fire.toBoolean)
              println(f"[bfdbg] cyc=$trCyc%6d COMMIT p$k robId=${c.robId.toInt}%2d pc=0x${c.pc.toLong & 0xffffffffL}%08x")
          }
        }
      }

      // debug-only, env-gated ISSUE-QUEUE trace (added for the Task P5.7 regression
      // root-cause, kept as the first direct view of IQ dependency state): every push
      // (both dispatch slots, with pdst/psrcB so an intra-push producer/consumer pair
      // is visible) plus, over an optional cycle window [IQDBG_LO, IQDBG_HI], a full
      // dump of every OCCUPIED slot -- robId, op, the static trigger bitmap, the
      // source physregs, and the dynamic LS/CPLX wait bits -- alongside sbInt.busy.
      // This is what showed the dependent's trigger being cleared (producer selected)
      // many cycles before the producer actually reached its EU. Zero cost unless
      // PORTED_TRACE_IQDBG is set.
      if (sys.env.contains("PORTED_TRACE_IQDBG")) {
        val lo = sys.env.getOrElse("IQDBG_LO", "0").toInt
        val hi = sys.env.getOrElse("IQDBG_HI", "999999").toInt
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          val iql = dut.iq.logic
          if (dut.iq.pushPort.valid.toBoolean) {
            val p0 = dut.iq.pushPort.payload(0); val p1 = dut.iq.pushPort.payload(1)
            println(f"[iqdbg] cyc=$trCyc%6d PUSHV s0 robId=${p0.robId.toInt}%2d op=${p0.uop.op.toEnum}%-12s " +
              f"pdst=${p0.uop.pdst.toInt}%2d/${p0.uop.pdstValid.toBoolean} psrcB=${p0.uop.psrcB.toInt}%2d/${p0.uop.psrcBValid.toBoolean} | " +
              f"s1en=${dut.iq.pushSlot1Port.toBoolean} s1 robId=${p1.robId.toInt}%2d op=${p1.uop.op.toEnum}%-12s " +
              f"pdst=${p1.uop.pdst.toInt}%2d/${p1.uop.pdstValid.toBoolean} psrcB=${p1.uop.psrcB.toInt}%2d/${p1.uop.psrcBValid.toBoolean}")
          }
          if (trCyc >= lo && trCyc <= hi) {
            println(f"[iqdbg] cyc=$trCyc%6d sbIntBusy=0x${iql.sbInt.busy.toBigInt.toString(16)}")
            for ((s, i) <- iql.slots.zipWithIndex) if (s.sel.toBoolean) {
              println(f"[iqdbg] cyc=$trCyc%6d   slot$i%2d robId=${s.hot.robId.toInt}%2d op=${s.hot.op.toEnum}%-12s " +
                f"trig=0x${s.triggers.toBigInt.toString(16)} psrcA=${s.hot.psrcA.toInt}%2d/${s.hot.psrcAValid.toBoolean} " +
                f"psrcB=${s.hot.psrcB.toInt}%2d/${s.hot.psrcBValid.toBoolean} " +
                f"lsW=${s.lsWait.toBoolean} cxW=${s.cplxWait.toBoolean}")
            }
          }
        }
      }

      var bkptFired = false
      if (bkptPcs.nonEmpty) {
        cd.onSamplings {
          for (k <- 0 until 2) {
            val c = dut.rob.logic.commitObs(k)
            if (c.fire.toBoolean && bkptPcs.contains(c.pc.toLong & 0xffffffffL)) bkptFired = true
          }
        }
      }

      var cyc = 0L
      var word = 0L
      while (word == 0 && !bkptFired && cyc < timeoutCycles) {
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
        if (bkptFired) PortedPass
        else if (word == 0) PortedHang(cyc)
        else if (word == PassWord) PortedPass
        else PortedFail(word)
    }
    outcome
  }
}
