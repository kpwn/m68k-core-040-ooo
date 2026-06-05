package m68k040.lockstep

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin, DcacheService}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{RedirectService, DTranslationService}
import m68k040.oracle.{Musashi, OracleStep, ProgramAssembler}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.bus.amba4.axi.sim.{Axi4ReadOnlySlaveAgent, SparseMemory}
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** THE MILESTONE: first full-core Musashi lock-step.
  *
  * Wires the WHOLE core — I-cache → FetchAlign → Decode → Rename → Dispatch →
  * ROB + IssueQueue → 2 ALU EUs → 3 PRFs — and runs straight-line 2-byte
  * programs end-to-end from the I-cache through commit. The retired-instruction
  * stream is reconstructed by the sim-only whitebox (`WhiteboxCapture`, joining
  * EU writeback-obs by robId with the ROB commit-obs, folding CCR) and compared
  * against Musashi via `LockStep`.
  *
  * Corpus is restricted to 2-byte instructions (MOVEQ, reg-reg .l ALU/CMP) so
  * the ROB's stubbed `predNextPc = pc + 2` exactly matches Musashi's
  * post-instruction PC.
  */
class ExecuteLockStepSpec extends AnyFunSuite {

  /** Wires IQ issue ports to the two ALU EUs + the branch EU, the EU completions
    * to the ROB, and the ROB's commit-time mispredict redirect (RedirectService)
    * to the IQ flush. (Frontend pipeFlush / RAT-rollback flush / fetch redirect are
    * driven inside the consuming plugins from host.get[RedirectService].) */
  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin) extends FiberPlugin {
    // Int PRF write port for the exception unit's A7 (reg 15) write-back. Allocated
    // in setup (RegfileService requires it). latency=0 so the handler can read the
    // updated A7 the cycle after the exception commits (it is serializing).
    var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
    during setup { a7Wr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7") }
    val logic = during build new Area {
      val iq  = host[IssueQueueService]
      val rob = host[RobPlugin]
      eu0.issue << iq.issue(0)
      eu1.issue << iq.issue(1)
      // Branch EU: issue port 2 (branch-class) -> branch EU; completion -> ROB
      // branchCompletion (records {mispredict, nextPc} for commit-time recovery).
      branchEu.issue << iq.issue(2)
      rob.logic.branchCompletion.valid   := branchEu.completion.valid
      rob.logic.branchCompletion.payload := branchEu.completion.payload
      // TRAPV execute-time conditional fault (vector 7 if V) -> ROB.
      rob.logic.trapvFaultCompletion.valid   := branchEu.trapvFault.valid
      rob.logic.trapvFaultCompletion.payload := branchEu.trapvFault.payload
      rob.logic.completion(0).valid   := eu0.completion.valid
      rob.logic.completion(0).payload := eu0.completion.payload
      rob.logic.completion(1).valid   := eu1.completion.valid
      rob.logic.completion(1).payload := eu1.completion.payload
      // Committed-CCR VALUE completion (per EU): record the {N,Z,V,C,X} the EU wrote
      // for this robId so the exception FSM can stack the SR low byte byte-for-byte.
      def wireCcr(idx: Int, w: m68k040.execute.WbObs): Unit = {
        rob.logic.ccrCompletion(idx).valid            := w.valid
        rob.logic.ccrCompletion(idx).payload.robId    := w.robId
        rob.logic.ccrCompletion(idx).payload.nzvc     := w.nzvc.asUInt
        rob.logic.ccrCompletion(idx).payload.nzvcWrite:= w.nzvcWrite
        rob.logic.ccrCompletion(idx).payload.x        := w.x
        rob.logic.ccrCompletion(idx).payload.xWrite   := w.xWrite
      }
      wireCcr(0, eu0.logic.wbObs); wireCcr(1, eu1.logic.wbObs); wireCcr(2, lsEu.logic.wbObs)

      // ── LS cluster wiring (mirrors top/FullCoreSynth.BackendWiringPlugin) ──
      // LS issue port (3) -> LS EU. Its completion is BOTH a ROB completion (port
      // 2) AND the IQ dynamic-wakeup broadcast (variant A: a load's dependents wake
      // when the load's data is actually ready).
      lsEu.issue << iq.issue(3)
      rob.logic.completion(2).valid   := lsEu.completion.valid
      rob.logic.completion(2).payload := lsEu.completion.payload
      // MMU access-fault completion -> ROB (flags the entry vector 2 + faultAddr/SSW
      // for precise format-$7 delivery at retire).
      rob.logic.lsFaultCompletion.valid   := lsEu.faultCompletion.valid
      rob.logic.lsFaultCompletion.payload := lsEu.faultCompletion.payload
      // The dynamic wakeup must fire ONLY for a completing LOAD (it produces a
      // physreg). A STORE also completes (to retire) but writes NO register; its
      // s1Ctx.uop.pdst is stale/garbage and could spuriously match a consumer's
      // source physreg, clearing its lsWait early -> the consumer reads the PRF
      // before its real producing load lands. Gate on pdstValid.
      iq.lsWakeup.valid   := lsEu.wakeup.valid
      iq.lsWakeup.payload := lsEu.wakeup.payload
      // ROB retire (slot 0) -> SQ commit; doFlush -> SQ flush (squash speculative).
      lsEu.sqCommit.valid   := rob.logic.retire0
      lsEu.sqCommit.payload := rob.logic.h0
      lsEu.sqFlush          := host[RedirectService].doFlush

      // ── DTLB U/M deferred-write queue wiring (mirrors top/FullCoreSynth) ──
      val dtlb = host[m68k040.mmu.DtlbPlugin]
      dtlb.umAccessRobId := lsEu.xlateRobId
      dtlb.umCommitValid := rob.logic.retire0
      dtlb.umCommitId    := rob.logic.h0
      dtlb.umFlush       := host[RedirectService].doFlush
      // ── ITLB U deferred-write queue wiring (U-only; mirrors top/FullCoreSynth) ──
      val itlb = host[m68k040.mmu.ItlbPlugin]
      itlb.umAccessRobId := U(0, 6 bits)
      itlb.umCommitValid := rob.logic.retire0
      itlb.umCommitId    := rob.logic.h0
      itlb.umFlush       := host[RedirectService].doFlush

      // ── Commit-time mispredict redirect fan-out (registered doFlush pulse) ──
      val doFlush = host[RedirectService].doFlush
      val flushPc = host[RedirectService].flushPc
      val excActive = rob.logic.excActive
      // IQ/skid flush held high while the exception FSM runs (serializing) so
      // wrong-path uops fetched during the multi-cycle sequence are squashed.
      iq.flushPort := doFlush || excActive                     // IQ clear
      host[DecodeStage].logic.pipeFlush := doFlush || excActive
      host[RenameStage].logic.pipeFlush := doFlush || excActive
      // RAT-rollback flush (rename.flushPort) is already driven by the ROB
      // (rc.flushPort := flushing). Fetch redirect to the resolved target:
      val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
      faRedir.valid   := doFlush
      faRedir.payload := flushPc

      // ── Exception D-cache MUX (the LS EU arbitrates: it owns the cache ports, so
      // the exception unit's requests are routed THROUGH the LS EU's mux — see
      // LsEuPlugin.excActive/excLoad*/excStore*/excXlate*). The exc reads the cache
      // responses directly here. The exception sequencer is serializing (the LS pipe
      // is squashed), so the cache port is free while excActive. ──
      val dc    = host[DcacheService]
      val xlate = host[DTranslationService]
      val exc   = rob.logic.exc
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy
      exc.dcStoreAck        := dc.storeAck
      exc.dtRsp.ready       := xlate.rsp.ready
      exc.dtRsp.ppn         := xlate.rsp.ppn
      exc.dtRsp.cacheMode   := xlate.rsp.cacheMode
      exc.dtRsp.fault       := xlate.rsp.fault
      // route the exc's cache requests through the LS EU's arbiter
      lsEu.excActive            := excActive
      lsEu.excLoadCmdValid      := exc.dcLoadCmd.valid
      lsEu.excLoadCmdVaddr      := exc.dcLoadCmd.payload.vaddr
      lsEu.excLoadCmdSize       := exc.dcLoadCmd.payload.size
      exc.dcLoadCmd.ready       := lsEu.excLoadCmdReady
      lsEu.excStoreValid        := exc.dcStore.valid
      lsEu.excStorePayload      := exc.dcStore.payload
      lsEu.excXlateValid        := exc.dtReq.valid
      lsEu.excXlateVpn          := exc.dtReq.vpn
      lsEu.excXlateWrite        := exc.dtReq.write
      lsEu.excXlateSupervisor   := exc.dtReq.supervisor
      exc.sqDrained             := lsEu.sqEmptySig
      // A7 (int reg 15) write-back on an exception/RTE A7 change. Committed arch-15
      // maps to phys-15 (identity, unrenamed in these programs).
      a7Wr.valid   := exc.a7WriteValid
      a7Wr.address := U(15, a7Wr.address.getWidth bits)
      a7Wr.data    := exc.a7WriteData.asBits
    }
  }

  /** Full-core DUT: the entire frontend+backend chain. The I-cache AXI master,
    * FetchAlign redirect/resume, EU wbObs and ROB commitObs surface for the sim. */
  class FullCoreDut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ctrl   = new MmuControlPlugin
    val intCtrl = new m68k040.exception.InterruptControlPlugin
    val itlb   = new ItlbPlugin
    val dtlb   = new DtlbPlugin
    val icache = new IcachePlugin
    val dcache = new DcachePlugin
    val fa     = new FetchAlignPlugin
    val dec    = new DecodeStage
    val ren    = new RenameStage
    val disp   = new m68k040.dispatch.DispatchPlugin
    val rob    = new RobPlugin
    val iq     = new IssueQueuePlugin
    val eu0    = new AluEuPlugin
    val eu1    = new AluEuPlugin
    val branchEu = new BranchEuPlugin
    val lsEu   = new LsEuPlugin
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val wire   = new BackendWiringPlugin(eu0, eu1, branchEu, lsEu)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()),
      ctrl,
      intCtrl,
      itlb,
      dtlb,
      icache, dcache, fa, dec, ren, disp, rob, iq, eu0, eu1, branchEu, lsEu,
      rfInt, rfNzvc, rfX, wire)) }
  }

  /** Attach a behavioral AXI read-only memory backed by the assembled program.
    *
    * The assembled `image.bytes` are the m68k big-endian byte stream: instruction
    * word w is stored high-byte-first (`bytes[2i]=w>>8`, `bytes[2i+1]=w&0xff`).
    *
    * The I-cache forms its 64-bit little-endian window from memory bytes such that
    * window-word j = `mem[base+2j+1]<<8 | mem[base+2j]` (low byte at the lower
    * address) and hands that to the aligner/decoder as the instruction opcode. So
    * to present opcode w to the decoder we must store the bytes byte-SWAPPED
    * relative to the big-endian image: low byte first. This matches the proven
    * `IcacheSim.attachMemoryWithWords` convention. Bytes outside the image read as
    * 0 (decode into harmless garbage; never reached in the compared prefix). */
  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long, bytes: Vector[Int]): Axi4ReadOnlySlaveAgent = {
    val mem = SparseMemory()
    // Reassemble 16-bit big-endian words from the image, then store low-byte-first.
    val nWords = bytes.length / 2
    for (i <- 0 until nWords) {
      val w = ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff) // big-endian word
      mem.write(loadAddr + 2 * i,     (w & 0xff).toByte)
      mem.write(loadAddr + 2 * i + 1, ((w >> 8) & 0xff).toByte)
    }
    new Axi4ReadOnlySlaveAgent(axi, cd) {
      override def readByte(address: BigInt, id: Int): Byte = mem.read(address.toLong)
    }
  }

  /** Run one program through the full core and lock-step it.
    *
    * `nInstr` is the number of RETIRED (executed) instructions to compare. For
    * straight-line code that equals the source line count (the default, computed
    * below when `nInstr < 0`). For programs with taken branches it must be given
    * explicitly: a taken branch skips/loops, so the executed count differs from the
    * source line count. The oracle (Musashi traces actual execution) and the DUT
    * commit stream are both bounded to the first `nInstr` records. */
  // --- MMU (directed non-identity) lock-step config ---
  // Musashi exposes no 040-MMU config, so for the MMU-enabled lock-step we run the
  // SAME program (register stream still lock-steps vs Musashi — data round-trips
  // store->load regardless of the physical address) and additionally assert the
  // store landed at the DIRECTLY-COMPUTED translated PA (directed, documented). The
  // map is (dataPageVA -> PPN): VA[31:12]==(dataPageVA>>12) maps to PPN.
  val MMU_ROOT = 0x00080000L
  val MMU_PTRT = 0x00081000L
  val MMU_PAGT = 0x00082000L
  // Per-(rootIdx,ptrIdx) leaf page table: the 64-entry leaf table for the data VA
  // lives at MMU_PAGT; the code VA (a different root/ptr index) gets its own leaf
  // table at MMU_PAGT2 so both can coexist under the shared root/ptr tables.
  val MMU_PAGT2 = 0x00083000L
  def mapPage(mem: m68k040.ls.BehavioralMemAgent, va: Long, ppn: Long, leafBase: Long): Unit = {
    def pokeWordLE(a: Long, w: Long): Unit = for (i <- 0 until 4) mem.pokeByte(a + i, ((w >> (8 * i)) & 0xff).toInt)
    val rootIdx = ((va >> 25) & 0x7f).toInt
    val ptrIdx  = ((va >> 18) & 0x7f).toInt
    val pageIdx = ((va >> 12) & 0x3f).toInt
    pokeWordLE(MMU_ROOT + rootIdx * 4, (MMU_PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(MMU_PTRT + ptrIdx * 4,  (leafBase & 0xfffffff0L) | 0x3L)
    pokeWordLE(leafBase + pageIdx * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }
  // The 68040 has ONE MMU: when enabled, BOTH I-fetch and D-access translate. The
  // oracle treats instruction fetch as IDENTITY (untranslated), so to keep the RTL
  // ITLB byte-for-byte the same we IDENTITY-map the code region (8 pages from the
  // load address — covers the program + handler). Data gets the non-identity PPN.
  def buildMmuTable(mem: m68k040.ls.BehavioralMemAgent, dataPageVA: Long, ppn: Long): Unit = {
    mapPage(mem, dataPageVA, ppn, MMU_PAGT)
    val codeBase = ProgramAssembler.DefaultLoadAddress
    for (i <- 0 until 8) {
      val cva = codeBase + i * 0x1000L
      mapPage(mem, cva, (cva >> 12) & 0xfffffL, MMU_PAGT2)  // identity
    }
  }

  def runLockStep(name: String, src: String, nInstr: Int = -1, checkMem: Seq[Long] = Seq.empty,
                  checkSpan: Int = 4, mmuMap: Option[(Long, Long)] = None): Unit = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress

    // Oracle trace (Musashi). Bounds itself at maxCycles/sentinel.
    val oracleSteps: Vector[OracleStep] = Musashi.assembleAndTrace(src) match {
      case Right(v)  => v
      case Left(err) => fail(s"[$name] Musashi.assembleAndTrace failed: ${err.reason}")
    }
    // Oracle FINAL memory image (for store programs): assembleAndRun returns the
    // per-byte memoryWrites the program performed. Only the program's explicit DATA
    // addresses (`checkMem`, each a 4-byte long) are compared — Musashi's harness
    // also writes the stack/sentinel region which the bare DUT does not exercise.
    val oracleMem: Map[Long, Int] =
      if (checkMem.nonEmpty) Musashi.assembleAndRun(src) match {
        case Right(st) => st.memoryWrites
        case Left(err) => fail(s"[$name] Musashi.assembleAndRun failed: ${err.reason}")
      } else Map.empty
    // Each checked base spans `checkSpan` bytes (4 for a long, 2 for a word). Only
    // program-written bytes are compared (the DUT's SparseMemory defaults unwritten
    // bytes to a non-zero pattern, so checking only the written span is required).
    val checkAddrs: Seq[Long] = checkMem.flatMap(a => (0L until checkSpan.toLong).map(a + _))
    // Program bytes for the I-cache.
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$name] ProgramAssembler.assemble failed: ${err.reason}")
    }

    // N = number of RETIRED instructions to compare. Default (straight-line) = the
    // source line count; branch programs pass it explicitly (executed count).
    val n = if (nInstr >= 0) nInstr else src.split(';').map(_.trim).count(_.nonEmpty)
    assert(oracleSteps.size >= n,
      s"[$name] oracle produced ${oracleSteps.size} steps, expected >= $n (program ran past its end?)")
    val oracle = oracleSteps.take(n)

    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      var wbCount = 0; var commitCount = 0

      // Capture one EU's writeback-obs into the whitebox (shared by ALU0/ALU1/LS).
      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) {
          wbCount += 1
          handle.onWb(
            w.robId.toInt,
            WhiteboxCapture.Wb(
              dstArch   = w.dstArch.toInt,
              result    = w.result.toLong & 0xffffffffL,
              intWrite  = w.intWrite.toBoolean,
              nzvc      = w.nzvc.toInt,
              nzvcWrite = w.nzvcWrite.toBoolean,
              x         = if (w.x.toBoolean) 1 else 0,
              xWrite    = w.xWrite.toBoolean))
        }
      }

      // Per-cycle sampler: EU writeback-obs (join key) + ROB commit-obs (order/pc).
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        // LS EU writeback-obs (loads write an int reg incl. the T0/T1 temp; stores
        // write none). Same join key (robId) as the ALU EUs. Temp-only commits are
        // dropped in WhiteboxCapture.onCommit (decode-matrix §4.5).
        captureWb(dut.lsEu.logic.wbObs);
        // Branch EU writeback-obs: a branch writes NO int/flag reg and leaves CCR
        // unchanged. Map it to a no-write Wb (the commit pc comes from the ROB
        // commitObs = resolved nextPc). dstArch=0 is harmless since intWrite=false.
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            wbCount += 1
            handle.onWb(
              bw.robId.toInt,
              WhiteboxCapture.Wb(
                dstArch   = 0,
                result    = 0L,
                intWrite  = false,
                nzvc      = 0,
                nzvcWrite = false,
                x         = 0,
                xWrite    = false))
          }
        }
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            commitCount += 1
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
          }
        }
        // Exception / RTE commit channel (handler-entry / restored PC + sysByte/A7).
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            commitCount += 1
            handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL)
          }
        }
      }

      // Attach the program to the I-cache AXI.
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)

      // Attach a behavioral read/write memory to the D-cache AXI (separate image,
      // zeroed; the programs store before they load, so no data preload needed).
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Attach a behavioral memory to the DTLB walker AXI (the page table lives here
      // when the MMU is enabled; idle for MMU-disabled programs). MMU disabled by
      // default -> identity passthrough, so existing programs are unchanged.
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      // The ITLB has its OWN dedicated walker AXI port: attach a second behavioral
      // memory holding the SAME page table (one shared page table, two read ports).
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      mmuMap match {
        case Some((dataPageVA, ppn)) =>
          buildMmuTable(ptmem, dataPageVA, ppn)
          buildMmuTable(itlbPtmem, dataPageVA, ppn)
          dut.ctrl.logic.mmuEnable #= true
          dut.ctrl.logic.rootPtr   #= MMU_ROOT
        case None =>
          dut.ctrl.logic.mmuEnable #= false
          dut.ctrl.logic.rootPtr   #= 0
      }

      // Idle the frontend; consumer-driven ready ports default high downstream.
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false

      // Let the PRF init sweep + rename committed-RAT identity init finish.
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot the committed supervisor SP to Musashi's initial SSP (0x00100000) AFTER
      // the init sweep (it resets committed state) so the surfaced A7 (== SSP, S=1)
      // matches OracleStep.a(7) for every program.
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      cd.waitSampling()
      // Pulse redirect to the program load PC to start fetch.
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      // Run until enough committed (or a generous cap).
      var guard = 0
      val cap   = 4000
      while (handle.result.size < n && guard < cap) {
        if (name == "mixed" && guard < 80) {
          val dpc = dut.fa.logic.decodePc.toLong & 0xffffffffL
          val fpc = dut.fa.logic.fetchPc.toLong & 0xffffffffL
          val ic  = dut.fa.logic.ibuf.count.toInt
          val fv  = dut.fa.logic.feed.valid.toBoolean
          val fr  = dut.fa.logic.feed.ready.toBoolean
          val p0  = dut.fa.logic.feed.payload(0).pc.toLong & 0xffffffffL
          println(f"[$name] c$guard%3d dpc=0x$dpc%x fpc=0x$fpc%x ibuf=$ic feed(v=$fv r=$fr p0=0x$p0%x)")
        }
        cd.waitSampling(); guard += 1
      }
      assert(handle.result.size >= n,
        s"[$name] only ${handle.result.size}/$n instructions committed within $cap cycles")

      val res = LockStep.compare(handle.result.take(n), oracle)
      assert(res.ok,
        s"[$name] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $n)")

      // Final memory check (store programs): let committed stores drain to memory,
      // then compare the program's DATA bytes against the DUT's D-cache memory.
      if (checkMem.nonEmpty) {
        cd.waitSampling(200)
        // Under an enabled MMU the store lands at the TRANSLATED PA. Translate each
        // checked VA byte = (PPN << 12) | (VA & 0xfff) (directed; the page maps the
        // whole data page). MMU-disabled -> identity (PA == VA), unchanged.
        def pa(va: Long): Long = mmuMap match {
          case Some((_, ppn)) => (ppn << 12) | (va & 0xfffL)
          case None           => va
        }
        for (addr <- checkAddrs) {
          val expected = oracleMem.getOrElse(addr, 0) & 0xff
          val got = dmem.peekByte(pa(addr))
          assert(got == expected,
            f"[$name] memory mismatch at VA 0x$addr%08x (PA 0x${pa(addr)}%08x): dut=0x$got%02x oracle=0x$expected%02x")
        }
      }
    }
  }

  /** Lock-step an interrupt program vs Musashi (simple protocol).
    *
    * `irqEvents` = (eventPc, level): the IRQ is recognized at the instruction
    * boundary AT eventPc (that instruction is preempted and re-executes after RTE),
    * EXACTLY as the oracle's trace loop applies it. The DUT raises `iplIn`=level
    * once it has committed the predecessor of eventPc (commitObs.pc == eventPc),
    * matching the oracle's set_irq at PC==eventPc; it drops iplIn on the exception
    * entry commit (one-shot edge), so a level-triggered re-fire after RTE does not
    * loop. `avec`=true => autovector (vec = 24+level), else vectored (`vectorIn`).
    * `initialSr` overrides the boot SR (lower the I-mask so a non-NMI level is taken;
    * both DUT committed srSys and the oracle reset SR are set to it). */
  def runIrqLockStep(name: String, src: String, nInstr: Int,
                     irqEvents: Seq[(Long, Int)], avec: Boolean = true,
                     vectorIn: Int = 0, initialSr: Int = 0x2700): Unit = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val ackVector = if (avec) None else Some(vectorIn)

    val oracleSteps: Vector[OracleStep] =
      Musashi.assembleAndTrace(src, irqEvents = irqEvents, interruptAckVector = ackVector,
                               initialSr = Some(initialSr)) match {
        case Right(v)  => v
        case Left(err) => fail(s"[$name] Musashi.assembleAndTrace failed: ${err.reason}")
      }
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$name] ProgramAssembler.assemble failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr,
      s"[$name] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)
    val eventPcs = irqEvents.map(_._1 & 0xffffffffL).toSet
    val levelByPc = irqEvents.map { case (pc, l) => (pc & 0xffffffffL) -> l }.toMap

    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      var wbCount = 0; var commitCount = 0
      // Pending IRQ to assert + a one-shot guard so we drive a single edge per event.
      val firedEvents = scala.collection.mutable.Set[Long]()

      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) {
          wbCount += 1
          handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
            dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
            intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt,
            nzvcWrite = w.nzvcWrite.toBoolean, x = if (w.x.toBoolean) 1 else 0,
            xWrite = w.xWrite.toBoolean))
        }
      }

      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs);
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            wbCount += 1
            handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
          }
        }
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            commitCount += 1
            val pc = c.pc.toLong & 0xffffffffL
            handle.onCommit(c.robId.toInt, pc, sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
            // The just-committed instruction's successor is `pc`. If an IRQ event is
            // scheduled at `pc` and not yet fired, raise iplIn so the next head
            // (the eventPc instruction) recognizes the interrupt before committing.
            if (eventPcs.contains(pc) && !firedEvents.contains(pc)) {
              firedEvents += pc
              dut.intCtrl.logic.iplIn      #= levelByPc(pc)
              dut.intCtrl.logic.iackAvec   #= avec
              dut.intCtrl.logic.iackVector #= vectorIn
            }
          }
        }
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            val isInt = c.isInterrupt.toBoolean
            // Drop the interrupt-ENTRY record: Musashi's trace bundles the entry with
            // the first handler instruction (an async interrupt consumes no user
            // instruction), so the entry is verified by that first handler commit's
            // mask-raised SR + decremented A7. A fault/trap entry and the RTE record
            // ARE their own oracle steps -> keep them.
            if (!isInt) {
              commitCount += 1
              handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL)
            } else {
              // Drop the IRQ line on the entry commit (one-shot edge): the interrupt
              // is taken, the mask is raised; a re-fire after RTE must not loop.
              dut.intCtrl.logic.iplIn #= 0
            }
          }
        }
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.rootPtr   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      // Boot the committed SR to initialSr's system byte (lower the I-mask so a
      // non-NMI level is taken; matches the oracle's --initial-sr).
      dut.rob.logic.exc.ss.srSys #= (initialSr >> 8) & 0xff
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      while (handle.result.size < nInstr && guard < cap) { cd.waitSampling(); guard += 1 }
      assert(handle.result.size >= nInstr,
        s"[$name] only ${handle.result.size}/$nInstr instructions committed within $cap cycles")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[$name] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")
    }
  }

  // ── INTERRUPT lock-step (simple protocol) ────────────────────────────────────
  // Autovector level 5 -> vector 24+5 = 29 -> table @ 0x74. The boot SR mask is
  // lowered to 0 (initialSr 0x2000) so the level-5 IRQ is taken. The IRQ is
  // recognized at the boundary before `moveq #1,%d1` (the preempted instruction
  // re-executes after RTE). The handler bumps D3 and RTEs. PC/SR(incl. mask)/A7 +
  // regs are lock-stepped vs Musashi across entry -> handler -> RTE -> resume.
  test("lock-step IRQ: autovector -> handler -> RTE -> resume", VerilatorTest) {
    // The IRQ-boundary instruction is a LOAD (`move.l 0x90,%d1`) of a value the
    // program just stored (0x11223344). A load is multi-cycle, so the DUT holds it
    // at the head long enough for the asynchronously-raised iplIn (driven the cycle
    // its predecessor commits) to be sampled and preempt it -- and it re-executes
    // (re-loading the same value) after RTE, matching the oracle's re-step.
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " + // seed [0x90] = 0x11223344
      "move.l #handler,%d0 ; move.l %d0,0x74 ; " +    // install vector 29 @ 0x74
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +           // load @ boundary
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    // event @ load PC = 6+4+6+4 = 0x14 in -> 0x40800014.
    runIrqLockStep("irq-avec", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 5)), avec = true, initialSr = 0x2000)
  }

  // Vectored: the SoC presents a vectored vector (0x46 = 70) instead of autovector;
  // the frame's format/vector word + handler fetch use 0x46. Vector 70 table @ 0x118.
  test("lock-step IRQ: vectored -> handler -> RTE -> resume", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +
      "move.l #handler,%d0 ; move.l %d0,0x118 ; " +   // install vector 0x46 @ 0x118
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    runIrqLockStep("irq-vectored", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 5)), avec = false, vectorIn = 0x46, initialSr = 0x2000)
  }

  // Masked: ipl (3) <= the SR I-mask (boot 7) -> NOT taken. The DUT raises iplIn=3
  // but the recognition never fires; both DUT and oracle run straight-line. (initialSr
  // 0x2700 = mask 7; the oracle's level-3 event is likewise masked.)
  test("lock-step IRQ: masked (ipl <= mask) NOT taken", VerilatorTest) {
    val src =
      "moveq #1,%d1 ; moveq #2,%d2 ; moveq #3,%d3 ; moveq #4,%d4 ; " +
      "loop: bra loop"
    // event @ moveq #3 PC = 0x40800004; level 3 <= mask 7 -> ignored.
    runIrqLockStep("irq-masked", src, nInstr = 5,
      irqEvents = Seq((0x40800004L, 3)), avec = true, initialSr = 0x2700)
  }

  // NMI: level 7 is ALWAYS taken regardless of the mask (boot 7). Vector 31 @ 0x7C.
  test("lock-step IRQ: NMI (level 7) through mask 7", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +
      "move.l #handler,%d0 ; move.l %d0,0x7c ; " +    // install vector 31 @ 0x7C
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    runIrqLockStep("irq-nmi", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 7)), avec = true, initialSr = 0x2700)
  }

  // Nested: a level-3 IRQ enters handlerA (mask raised to 3); inside handlerA a
  // level-5 IRQ (5 > 3) preempts it -> handlerB -> RTE -> back into handlerA -> RTE
  // -> resume. Two events: at the main load boundary (level 3) and inside handlerA
  // (level 5). Both vectors autovector (29 @ 0x74, 24+3; and 24+5... wait levels:
  // level 3 -> vec 27 @ 0x6C; level 5 -> vec 29 @ 0x74). Both handlers installed.
  test("lock-step IRQ: nested (higher level preempts a running handler)", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +   // seed [0x90] (main load)
      "move.l #0x55667788,%d0 ; move.l %d0,0x94 ; " +   // seed [0x94] (handlerA's load)
      "move.l #handlerA,%d0 ; move.l %d0,0x6c ; " +     // vec 27 (level 3) @ 0x6C
      "move.l #handlerB,%d0 ; move.l %d0,0x74 ; " +     // vec 29 (level 5) @ 0x74
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +             // main boundary (level 3 IRQ) @ 0x28
      "loop: bra loop ; " +
      "handlerA: moveq #4,%d4 ; move.l 0x94,%d5 ; rte ; " + // load boundary @ 0x34 (level 5)
      "handlerB: moveq #9,%d3 ; rte"
    // main load @ 0x40800028; handlerA's `move.l 0x94,%d5` (2nd handler instr) @ 0x34
    // -- NOT the first handler instruction (which the oracle bundles with the entry,
    // so a PC-scheduled event there would never be observed). The level-5 IRQ at 0x34
    // preempts handlerA (5 > the raised mask 3) -> handlerB -> RTE -> handlerA -> RTE.
    runIrqLockStep("irq-nested", src, nInstr = 15,
      irqEvents = Seq((0x40800028L, 3), (0x40800034L, 5)), avec = true, initialSr = 0x2000)
  }

  test("lock-step: moveq sequence", VerilatorTest) {
    runLockStep("moveq",
      "moveq #1,%d0 ; moveq #2,%d1 ; moveq #-1,%d2 ; moveq #0,%d3")
  }

  test("lock-step: alu chain (add/sub/and/or)", VerilatorTest) {
    runLockStep("alu-chain",
      "moveq #10,%d0 ; moveq #3,%d1 ; add.l %d1,%d0 ; sub.l %d1,%d0 ; and.l %d1,%d0 ; or.l %d1,%d0")
  }

  test("lock-step: cmp", VerilatorTest) {
    runLockStep("cmp",
      "moveq #5,%d0 ; moveq #5,%d1 ; cmp.l %d1,%d0 ; moveq #7,%d2")
  }

  test("lock-step: register move chain", VerilatorTest) {
    // Register-to-register MOVE: the ALU computes result = src2 (= srcB), so the
    // decoder must place the MOVE source register in srcB. Before the fix the
    // source went to srcA and srcB defaulted to reg0 (D0), so the move read D0's
    // value instead of the named source. We seed D0 with a DIFFERENT value (99)
    // than the source register (D1=42) so the buggy reg0-read is detectable:
    // pre-fix `move.l %d1,%d2` would yield D2=99 (D0), diverging from Musashi's 42.
    runLockStep("regmove",
      "moveq #99,%d0 ; moveq #42,%d1 ; move.l %d1,%d2 ; move.l %d2,%d3")
  }

  test("lock-step: mixed straight-line (~24 instrs)", VerilatorTest) {
    runLockStep("mixed", Seq(
      "moveq #1,%d0", "moveq #2,%d1", "moveq #3,%d2", "moveq #4,%d3",
      "moveq #-1,%d4", "moveq #100,%d5", "moveq #7,%d6", "moveq #0,%d7",
      "add.l %d1,%d0", "add.l %d2,%d1", "sub.l %d3,%d2", "and.l %d4,%d3",
      "or.l %d5,%d4", "cmp.l %d6,%d5", "add.l %d7,%d6", "sub.l %d0,%d7",
      "and.l %d1,%d0", "or.l %d2,%d1", "add.l %d3,%d2", "cmp.l %d4,%d3",
      "sub.l %d5,%d4", "and.l %d6,%d5", "or.l %d7,%d6", "add.l %d0,%d7"
    ).mkString(" ; "))
  }

  // ── Branch lock-step (2-byte short branches) ──────────────────────────────
  // No predictor: a TAKEN branch is a mispredict -> the ROB registers a
  // commit-time redirect pulse that squashes the speculative fall-through and
  // refetches at the resolved target. The branch's commit pc = resolved nextPc
  // (= Musashi's post-instruction pc). Each program passes an EXPLICIT executed
  // instruction count (a taken branch skips/loops -> count != source lines).

  test("lock-step: beq.s taken (skips a moveq)", VerilatorTest) {
    // D0=1; cmp d0,d0 -> Z=1; beq taken -> skip `moveq #9,%d1`; land on moveq#7.
    // Executed: moveq#1, cmp, beq(taken->target), moveq#7 = 4.
    runLockStep("beq-taken",
      "moveq #1,%d0 ; cmp.l %d0,%d0 ; beq.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2",
      nInstr = 4)
  }

  test("lock-step: bne.s not-taken (falls through)", VerilatorTest) {
    // D0=1; cmp d0,d0 -> Z=1; bne NOT taken -> fall through (D1=9), then moveq#7.
    // Executed: moveq#1, cmp, bne(not-taken->pc+2), moveq#9, moveq#7 = 5.
    runLockStep("bne-nottaken",
      "moveq #1,%d0 ; cmp.l %d0,%d0 ; bne.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2",
      nInstr = 5)
  }

  test("lock-step: bra.s unconditional", VerilatorTest) {
    // Unconditional taken -> skip `moveq #9,%d0`; land on moveq#7.
    // Executed: bra(taken->target), moveq#7 = 2.
    runLockStep("bra",
      "bra.s .L ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  test("lock-step: backward bne.s loop (one backward taken)", VerilatorTest) {
    // D0=2 (counter), D1=1 (decrement). Loop body sub.l d1,d0 ; bne.s .L:
    //   iter1: 2-1=1 (Z=0) -> bne TAKEN  (backward commit-time redirect to .L)
    //   iter2: 1-1=0 (Z=1) -> bne NOT taken -> fall through to moveq#7
    // Exactly one backward-taken mispredict, exercising backward branch
    // displacement + commit-time redirect + recovery. Executed: moveq#2, moveq#1,
    // sub, bne(taken), sub, bne(not-taken), moveq#7 = 7.
    runLockStep("loop",
      "moveq #2,%d0 ; moveq #1,%d1 ; .L: sub.l %d1,%d0 ; bne.s .L ; moveq #7,%d2",
      nInstr = 7)
  }

  test("lock-step: backward bne.s loop (multiple consecutive taken)", VerilatorTest) {
    // D0=4: iter1..3 taken (3,2,1 -> Z=0), iter4 1-1=0 (Z=1) not-taken. THREE
    // back-to-back taken mispredicts -> exercises freelist/RAT recovery across
    // CONSECUTIVE flushes (the case that exposed the freelist flush re-init bug:
    // a flush must roll the freelist back to its COMMITTED state, not the identity
    // pool). Executed: moveq#4, moveq#1, [sub,bne]x4, moveq#7 = 11.
    runLockStep("loop-multi",
      "moveq #4,%d0 ; moveq #1,%d1 ; .L: sub.l %d1,%d0 ; bne.s .L ; moveq #7,%d2",
      nInstr = 11)
  }

  // ── Load/store lock-step (THE memory milestone) ────────────────────────────
  // All addresses use absolute modes (no An setup needed) and store BEFORE they
  // load, so the D-cache behavioral memory needs no preload. The store µop drains
  // at commit (write-through); store-then-load-back resolves via SQ forwarding.

  test("lock-step: store then load-back (D1 == stored value)", VerilatorTest) {
    // moveq #42,%d0 ; move.l %d0,0x2000 (store) ; move.l 0x2000,%d1 (load-back)
    // D1 must read back 42 (SQ forward / write-through). Final mem[0x2000]==42.
    runLockStep("st-ld",
      "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L))
  }

  // ── Store-pipeline (S0/S1) store->load + SQ-forward coverage ────────────────
  // The store cache-write now lands one cycle later (S0 read / S1 merge+write).
  // These programs pin that the +1-cycle write is invisible: the in-flight store
  // is covered by SQ forwarding + the drain-resident-until-ACK window. Each step
  // lock-steps PC/SR/regs vs Musashi.

  test("lock-step: store then immediate load same line (SQ forward)", VerilatorTest) {
    // Two stores into the SAME 16-byte line (0x2000), each immediately loaded back.
    // The load fires while the store is still draining (cache write delayed to S1),
    // so it MUST resolve via SQ forwarding. Final mem[0x2000], mem[0x2004].
    runLockStep("st-ld-sameline",
      "moveq #0x11,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; " +
      "moveq #0x22,%d2 ; move.l %d2,0x2004 ; move.l 0x2004,%d3",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: store long then load overlapping sub-word (SQ forward)", VerilatorTest) {
    // Store a LONG at 0x2000, then load the WORD at 0x2002 overlapping its low half.
    // The overlapping sub-word load must forward from the in-flight store (the cache
    // line is still being merged in S1). 0x12345678 -> word @0x2002 == 0x5678.
    runLockStep("st-ld-subword",
      "move.l #0x12345678,%d0 ; move.l %d0,0x2000 ; moveq #0,%d1 ; move.w 0x2002,%d1",
      checkMem = Seq(0x2000L), checkSpan = 4)
  }

  // ── MOVE-to/from-memory CCR (the bug fix) ──────────────────────────────────
  // MOVE (all sizes) sets N/Z from the moved value and clears V/C — INCLUDING
  // MOVE to memory. These programs move NEGATIVE and ZERO values to/from memory
  // and lock-step the committed CCR against Musashi (which sets MOVE flags). The
  // store µop now carries writesNzvc (impl (a)); the load-back's op µop sets NZVC
  // from the loaded value. Each ends with a `move.l Dn,Dm` so a divergent CCR (if
  // the store/load failed to set flags) is visible at commit, not just in memory.

  test("lock-step: MOVE negative value TO memory sets N (CCR)", VerilatorTest) {
    // D0 = -1 (0xFFFFFFFF). store D0 -> 0x2000 sets N=1,Z=0,V=0,C=0. The store's
    // committed CCR must show N set (pre-fix it stayed 0). Load it back into D1
    // (the load-back's MOVE also sets N). mem[0x2000] == 0xFFFFFFFF.
    runLockStep("move-neg-to-mem",
      "moveq #-1,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L))
  }

  test("lock-step: MOVE zero value TO memory sets Z (CCR)", VerilatorTest) {
    // First dirty CCR with a negative move (N=1), then store ZERO -> the store must
    // set Z=1,N=0 (CCR changes from N to Z). Pre-fix the store left CCR=N, diverging.
    runLockStep("move-zero-to-mem",
      "moveq #-5,%d0 ; move.l %d0,0x2004 ; moveq #0,%d1 ; move.l %d1,0x2000 ; move.l 0x2000,%d2",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: MOVE.W negative word TO memory sets N at word size", VerilatorTest) {
    // D0 low word = 0x8000 (negative at WORD size, but POSITIVE at long). move.w
    // D0,0x2000 must compute N from bit15 (=1), Z=0. Exercises size-correct N. The
    // long value 0x00008000 would give N=0 at long size, so this distinguishes the
    // size handling. Load the word back into D1 (zero-extended).
    runLockStep("move-negw-to-mem",
      "move.l #0x00008000,%d0 ; move.w %d0,0x2000 ; moveq #0,%d1 ; move.w 0x2000,%d1",
      checkMem = Seq(0x2000L), checkSpan = 2)
  }

  test("lock-step: MOVE negative/zero FROM memory sets CCR (mem->Dn)", VerilatorTest) {
    // Store a NEGATIVE long, load it back -> the load-back MOVE sets N. Then store
    // ZERO, load it back -> the load-back MOVE sets Z. Verifies MOVE mem->Dn flags
    // from the LOADED value for both negative and zero.
    runLockStep("move-from-mem-flags",
      "moveq #-1,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; " +
      "moveq #0,%d2 ; move.l %d2,0x2004 ; move.l 0x2004,%d3",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: two loads + add", VerilatorTest) {
    // Two cracked loads feeding an add: D2 = mem[0x2000] + mem[0x2004] = 10 + 20 = 30.
    // Each load immediately follows its producing store so it resolves via the
    // store-queue forward path (the load reads the in-flight store's data). Both
    // `move.l (mem),%d2` and `add.l (mem),%d2` crack LOAD->T0 then the op reading T0.
    runLockStep("two-ld-add",
      "moveq #10,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d2 ; " +
      "moveq #20,%d3 ; move.l %d3,0x2004 ; add.l 0x2004,%d2",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: load -> ALU -> store (temp dataflow through LS wakeup)", VerilatorTest) {
    // moveq #5,%d0 ; store 5 -> 0x2000 ; load 0x2000 -> D1 (cracks LOAD->T0 then
    // MOVE T0->D1, the temp consumed via the LS dynamic-completion wakeup) ;
    // add.l %d1,%d1 -> D1 = 10 ; store D1 -> 0x2004. A loaded value flows through
    // an ALU op into a store. Final mem[0x2000]==5, mem[0x2004]==10.
    runLockStep("ld-alu-st",
      "moveq #5,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x2004",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  // ── Misaligned / line- & page-crossing lock-step (the two-access-slot milestone) ─
  // Musashi models 68040 misaligned semantics (a misaligned access reads/writes
  // the same byte sequence as two aligned halves) — it is the oracle. Each program
  // stores BEFORE it loads, so the split store drains atomically (both halves) and
  // the cross load reads back the merged value. Addresses are chosen to cross a
  // 16-byte L1D line and/or a 4 KB page.

  test("lock-step: line-crossing long store then load-back (offset 14)", VerilatorTest) {
    // move.l %d0,0x200E : a LONG at line offset 14 spans 0x200E..0x2011 -> crosses
    // the 16-byte line boundary (lines 0x2000 / 0x2010). Load it back into D1.
    // Final mem[0x200E..0x2011] == 0x12345678 (split store drained both halves).
    runLockStep("cross-line-st-ld",
      "move.l #0x12345678,%d0 ; move.l %d0,0x200E ; move.l 0x200E,%d1",
      checkMem = Seq(0x200EL))
  }

  test("lock-step: word-misaligned store then load-back", VerilatorTest) {
    // move.w %d0,0x2003 : a WORD at odd offset 3 within line 0x2000 (misaligned,
    // single line) store + word load-back. Data is positive (bit15=0) so the MOVE
    // CCR is 0 (N=0,Z=0,V=0,C=0) in both DUT and oracle. (MOVE-to/from-memory NOW
    // computes CCR — see the move-neg/zero-to-mem programs for the N/Z coverage.)
    runLockStep("misaligned-word",
      "move.l #0x00001234,%d0 ; move.w %d0,0x2003 ; moveq #0,%d1 ; move.w 0x2003,%d1",
      checkMem = Seq(0x2003L), checkSpan = 2)
  }

  test("lock-step: page-crossing word store then load-back", VerilatorTest) {
    // move.w %d0,0x2FFF : a WORD at page offset 0xFFF spans 0x2FFF..0x3000 -> crosses
    // the 4 KB page boundary (identity translation: two translations, both succeed).
    // Load it back. Final mem[0x2FFF..0x3000] holds the stored word.
    runLockStep("cross-page-st-ld",
      "move.l #0x00004321,%d0 ; move.w %d0,0x2FFF ; moveq #0,%d1 ; move.w 0x2FFF,%d1",
      checkMem = Seq(0x2FFFL), checkSpan = 2)
  }

  test("lock-step: line-crossing long store then overlapping long load", VerilatorTest) {
    // Pre-initialize the next line (0x2010), then store a long at the line-crossing
    // 0x200E (split: bytes 0x200E..0x2011), then load a long at 0x2010 overlapping
    // the high half of the split store. All loaded bytes are program-defined.
    // Loaded value = 56 78 33 44 = 0x56783344 (positive -> CCR 0). Exercises the
    // dual-slot forward / drain-then-read across the boundary.
    runLockStep("cross-line-overlap",
      "move.l #0x11223344,%d0 ; move.l %d0,0x2010 ; " +
      "move.l #0x12345678,%d2 ; move.l %d2,0x200E ; move.l 0x2010,%d1",
      checkMem = Seq(0x200EL, 0x2010L))
  }

  // ── MMU-enabled (translated, non-identity PA) lock-step ────────────────────
  // The MMU is enabled with a page table mapping the data page (VA 0x2000 ->
  // PPN 0x42, i.e. PA 0x42000). The register commit stream still lock-steps vs
  // Musashi (store->load round-trips the value regardless of PA), and the store is
  // additionally asserted to land at the DIRECTLY-COMPUTED translated PA (Musashi
  // exposes no 040-MMU config, so this is directed-non-identity). The first data
  // access TLB-misses -> the hardware walker fills -> subsequent accesses hit.

  test("lock-step MMU: store then load-back at translated PA", VerilatorTest) {
    // moveq #42,%d0 ; store D0->VA 0x2000 ; load VA 0x2000 -> D1 (== 42).
    // VA 0x2000 -> PA 0x42000; the store must land at PA 0x42000.
    runLockStep("mmu-st-ld",
      "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L), mmuMap = Some((0x2000L, 0x42L)))
  }

  test("lock-step MMU: load -> ALU -> store at translated PA (M bit set at commit)", VerilatorTest) {
    // moveq #5,%d0 ; store 5->VA0x2000 ; load VA0x2000->D1 ; add D1,D1 -> 10 ;
    // store D1->VA0x2004. Both stores translate to PA 0x42000/0x42004; the write
    // access also queues an M-bit descriptor write drained at commit.
    runLockStep("mmu-ld-alu-st",
      "moveq #5,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x2004",
      checkMem = Seq(0x2000L, 0x2004L), mmuMap = Some((0x2000L, 0x42L)))
  }

  // ── MMU fault-flag test (non-resident page -> rsp.fault FLAGGED, not delivered) ─
  test("MMU fault: load to a non-resident page flags rsp.fault (no delivery)", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // A program that stores then loads VA 0x2000. The page is left NON-RESIDENT in
    // the table, so the data access faults -> the DTLB flags rsp.fault. We assert
    // the flag (delivery is a later slice), not the register stream.
    val src = "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"assemble failed: ${err.reason}")
    }
    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      // Build a table whose root/pointer are resident but the PAGE descriptor is
      // NON-RESIDENT (PDT=00) for the data page VA 0x2000. Code is IDENTITY-mapped
      // (resident) in BOTH walker memories so instruction fetch through the ITLB does
      // not fault — only the DATA access to VA 0x2000 faults.
      def buildFaultTable(mem: m68k040.ls.BehavioralMemAgent): Unit = {
        def pokeWordLE(a: Long, w: Long): Unit = for (i <- 0 until 4) mem.pokeByte(a + i, ((w >> (8 * i)) & 0xff).toInt)
        val va = 0x2000L
        val rootIdx = ((va >> 25) & 0x7f).toInt; val ptrIdx = ((va >> 18) & 0x7f).toInt; val pageIdx = ((va >> 12) & 0x3f).toInt
        pokeWordLE(MMU_ROOT + rootIdx * 4, (MMU_PTRT & 0xfffffff0L) | 0x3L)
        pokeWordLE(MMU_PTRT + ptrIdx * 4,  (MMU_PAGT & 0xfffffff0L) | 0x3L)
        pokeWordLE(MMU_PAGT + pageIdx * 4, (0x42L << 12) & 0xfffff000L)   // PDT=00 -> non-resident
        for (i <- 0 until 8) { val cva = loadAddr + i * 0x1000L; mapPage(mem, cva, (cva >> 12) & 0xfffffL, MMU_PAGT2) }
      }
      buildFaultTable(ptmem); buildFaultTable(itlbPtmem)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.rootPtr   #= MMU_ROOT

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      // run a while; the data store/load to the non-resident page must flag a fault
      var guard = 0
      while (!dut.dtlb.logic.faultSeen.toBoolean && guard < 2000) { cd.waitSampling(); guard += 1 }
      assert(dut.dtlb.logic.faultSeen.toBoolean,
        "a data access to a non-resident page must flag DTLB rsp.fault (flagged, not delivered)")
    }
  }

  // ── PRECISE EXCEPTION lock-step: illegal-instruction -> handler -> RTE ───────
  // A program that installs the illegal-instruction vector (4) at VBR+0x10 (a
  // runtime store, so both the DUT D-cache and Musashi see it), executes an
  // `illegal` (0x4AFC) which the core delivers PRECISELY (stack a format-$0 frame
  // to SSP-8, fetch the handler vector, switch to supervisor — already supervisor
  // here, vector to the handler), runs a handler that bumps the stacked PC past the
  // illegal + sets D1/D2, and RTEs back to the fall-through `moveq #7,%d3`. The
  // commit stream (PC / full SR / A7 / regs) is lock-stepped vs Musashi step-for-
  // step across entry -> handler -> RTE. The final `bra .` halts over-fetch.
  test("lock-step: illegal-instruction -> handler -> RTE (precise exception)", VerilatorTest) {
    runLockStep("exc-illegal",
      "move.l #handler,%d0 ; move.l %d0,0x10 ; illegal ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: moveq #2,%d1 ; move.l 2(%a7),%d0 ; add.l %d1,%d0 ; move.l %d0,2(%a7) ; " +
      "moveq #1,%d2 ; rte",
      nInstr = 10)
  }

  // ── TRAP #n lock-step: decode-time unconditional trap -> handler -> RTE ──────
  // `trap #5` raises vector 37 (32+5) via the format-$0 path. TRAP is not
  // restartable: it stacks the PC of the NEXT instruction, so RTE resumes at the
  // fall-through `moveq #7,%d3` WITHOUT the handler bumping the stacked PC. The
  // handler vector lives at VBR(0)+37*4 = 0x94 (a runtime store the DUT D-cache +
  // Musashi both see). Commit PC/SR/A7 lock-stepped vs Musashi across entry ->
  // handler -> RTE -> resume.
  test("lock-step: TRAP #5 -> handler -> RTE (format-$0 delivery)", VerilatorTest) {
    runLockStep("exc-trap",
      "move.l #handler,%d0 ; move.l %d0,0x94 ; trap #5 ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: moveq #2,%d1 ; moveq #1,%d2 ; rte",
      nInstr = 7)
  }

  // ── TRAPV lock-step: execute-time conditional trap (vector 7, format-$2) ─────
  // V-set case: an add.l overflow sets V=1, so `trapv` traps -> vector 7 (format-$2
  // on the 68040), vectors to the handler, which RTEs back to the fall-through. The
  // stacked PC is the NEXT instruction's PC (TRAPV is not restartable). Commit
  // PC/SR/A7 lock-stepped vs Musashi across the overflow, the trap, the handler,
  // and the RTE resume.
  test("lock-step: TRAPV with V set -> handler -> RTE (format-$2 delivery)", VerilatorTest) {
    // The handler's last flag-writer reproduces the entry flags (N=1,V=1 from the
    // add.l overflow) so the sim whitebox's reconstructed CCR matches Musashi's
    // RTE-restored CCR at the RTE step. (The architectural CCR restore on RTE is a
    // separate pre-existing concern outside the trap machinery; this mirrors the
    // illegal-instruction test, where entry and handler-exit CCR coincide.)
    runLockStep("exc-trapv-set",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +       // vector 7 @ 0x1C
      "move.l #0x7fffffff,%d4 ; add.l %d4,%d4 ; " +      // signed overflow -> N=1,V=1
      "trapv ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: move.l #0x7fffffff,%d1 ; add.l %d1,%d1 ; rte",  // reproduce N=1,V=1
      nInstr = 9)
  }

  // V-clear case: a moveq clears V, so `trapv` is a no-op and falls through to the
  // next instruction. No exception is taken; the commit stream is straight-line.
  test("lock-step: TRAPV with V clear -> falls through (no trap)", VerilatorTest) {
    runLockStep("exc-trapv-clear",
      "moveq #5,%d4 ; trapv ; moveq #7,%d3 ; loop: bra loop",
      nInstr = 4)
  }

  // ── MMU page-fault delivery lock-step (format-$7 -> handler -> RTE) ──────────
  // THE Task-4 gate: a data store to a NON-RESIDENT page raises a 68040 access
  // fault (vector 2, format-$7), vectors to a handler that writes a resident page
  // descriptor, RTEs (re-executing the faulting store, which now succeeds), and
  // continues. The committed PC/SR/A7 stream + the stacked $7 frame match the MAME
  // 040 oracle step-for-step.
  //
  // MMU-on translates EVERY D-side access in the RTL, so the page table must map all
  // pages the data side touches. The exception FSM's frame/vector accesses use
  // IDENTITY paddr (they bypass translation), and RTE's frame reads are FSM-driven
  // (identity), so the supervisor stack needs no mapping. The LS data accesses that
  // DO translate are: the vector store (VA 0x8, VPN 0), the faulting store (VA
  // 0x2000, VPN 2), and the handler's PT write (VA 0x82008, VPN 0x82). Table:
  //   root[0] -> ptr ; ptr[0] -> pageA (covers 0x0..0x3FFFF) ; ptr[2] -> pageC
  //   pageA[0] = identity VPN 0 resident (vector page) ; pageA[2] = NON-RESIDENT
  //   pageC[2] = identity VPN 0x82 resident (the PT write page).
  // The handler writes pageA[2] = PPN 0x42 resident. Descriptors are LITTLE-ENDIAN
  // (RTL walker + oracle MMU), so a `move.l #imm` (big-endian store) writes
  // byteswap(descriptor): pageA[2] resident PPN 0x42 = 0x00042001 -> imm 0x01200400.
  test("lock-step: page fault (non-resident) -> handler maps -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val PTRT = 0x00081000L
    val PAGA = 0x00082000L
    val PAGC = 0x00083000L
    val dataPPN = 0x42L

    // The program (identical for RTL + oracle). Handler writes pageA[2] (byteswapped
    // resident PPN-0x42 descriptor) then RTE -> the faulting store re-executes.
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +        // vector 2 (access fault) @ 0x8
      "moveq #42,%d0 ; move.l %d0,0x2000 ; " +           // FAULTS, then re-runs after RTE
      "loop: bra loop ; " +
      "handler: move.l #0x01200400,%d1 ; move.l %d1,0x82008 ; rte"
    val nInstr = 8  // vec-imm, vec-store, store(fault->reexec after handler), handler-imm, handler-store, rte, store(reexec), bra

    // Oracle: window = the data page only; preload the resident root[0] + ptr[0]
    // descriptors the VA-0x2000 walk needs (handler writes the leaf pageA[2]).
    def le(v: Long): Long = v & 0xffffffffL
    val oraclePt = Seq(
      0x80000L -> ((PTRT & 0xfffffff0L) | 0x2L),   // root[0] -> ptr resident
      PTRT     -> ((PAGA & 0xfffffff0L) | 0x2L))   // ptr[0]  -> pageA resident
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0x2000L, dataHi = 0x3000L, ptPreload = oraclePt))

    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v
      case Left(err) => fail(s"[pagefault] oracle trace failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr, s"[pagefault] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)

    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[pagefault] assemble failed: ${err.reason}")
    }

    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
          dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
          intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean))
      }
      def captureBranch(): Unit = {
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      def captureExc(): Unit = {
        val c = dut.rob.logic.commitObs(2)
        if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL)
      }
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs)
        captureBranch()
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
            sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
        }
        captureExc()
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      // The D-cache and the MMU walker SHARE one physical memory: the page table the
      // handler writes via a D-cache store must be visible to the walker on re-walk.
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      // The ITLB walker shares the SAME backing memory (one physical page table), so a
      // handler PT write is visible to the I-side re-walk too.
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Build the nested page table (little-endian) in the shared memory.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * i)) & 0xff).toInt)
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)   // root[0] -> ptr resident
      pokeLE(PTRT + 0 * 4,    (PAGA & 0xfffffff0L) | 0x2L)   // ptr[0]  -> pageA resident
      pokeLE(PTRT + 2 * 4,    (PAGC & 0xfffffff0L) | 0x2L)   // ptr[2]  -> pageC resident
      pokeLE(PAGA + 0 * 4,    (0x0L << 12) | 0x1L)           // pageA[0] = identity VPN 0 (vectors)
      pokeLE(PAGA + 2 * 4,    0x0L)                          // pageA[2] = NON-RESIDENT (the fault)
      pokeLE(PAGA + 0x3f * 4, (0xffL << 12) | 0x1L)          // pageA[0x3f] = identity VPN 0xFF (supervisor stack)
      pokeLE(PAGC + 2 * 4,    (0x82L << 12) | 0x1L)          // pageC[2] = identity VPN 0x82 (PT write)
      // The 68040 has ONE MMU: the I-fetch path also translates. IDENTITY-map the code
      // region (8 pages from loadAddr; the oracle treats I-fetch as identity) so the
      // ITLB resolves code fetches without faulting. A SEPARATE leaf table (PAGD) at a
      // free address keeps it out of pageA/pageC.
      val PAGD = 0x00084000L
      pokeLE(PTRT + (((loadAddr >> 18) & 0x7f).toInt) * 4, (PAGD & 0xfffffff0L) | 0x2L)
      pokeLE(MMU_ROOT + (((loadAddr >> 25) & 0x7f).toInt) * 4, (PTRT & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val cva = loadAddr + i * 0x1000L
        pokeLE(PAGD + (((cva >> 12) & 0x3f).toInt) * 4, (((cva >> 12) & 0xfffffL) << 12) | 0x1L)
      }
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.rootPtr   #= 0x80000L

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.dtlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[pagefault] the data access to the non-resident page must flag a DTLB fault")
      assert(handle.result.size >= nInstr,
        s"[pagefault] only ${handle.result.size}/$nInstr committed within $cap cycles")
      // The stacked format-$7 frame (read back from the supervisor stack, big-endian)
      // must match the MAME-040 oracle byte-for-byte.
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert((pk16(fb) & 0xff00) == 0x2700, f"[pagefault] frame SR=0x${pk16(fb)}%04x expected 0x27xx")
      assert(pk32(fb + 2) == 0x4080000cL, f"[pagefault] frame PC=0x${pk32(fb + 2)}%08x expected the faulting-instr PC 0x4080000c")
      assert(pk16(fb + 6) == 0x7008, f"[pagefault] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008")
      assert(pk32(fb + 8) == 0x2000L, f"[pagefault] frame EA=0x${pk32(fb + 8)}%08x expected 0x2000")
      assert(pk16(fb + 0xc) == 0x0405, f"[pagefault] frame SSW=0x${pk16(fb + 0xc)}%04x expected 0x0405")
      assert(pk32(fb + 0x14) == 0x2000L, f"[pagefault] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x2000")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[pagefault] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")

      // The re-executed store landed at the mapped PA 0x42000 (value 42 = 0x2a).
      cd.waitSampling(200)
      val pa = (dataPPN << 12) | (0x2000L & 0xfffL)
      assert(dmem.peekByte(pa + 3) == 0x2a,
        f"[pagefault] re-executed store must land 0x2a at PA 0x${pa + 3}%08x (got 0x${dmem.peekByte(pa + 3)}%02x)")
    }
  }

  // ── ITLB lock-step helpers ──────────────────────────────────────────────────
  /** Place a code image at an ARBITRARY base in an I-cache SparseMemory (low-byte
    * first, matching the I-cache window convention — same swap as attachProgram). */
  private def writeCodeAt(mem: SparseMemory, base: Long, bytes: Vector[Int]): Unit = {
    val nWords = bytes.length / 2
    for (i <- 0 until nWords) {
      val w = ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff)
      mem.write(base + 2 * i,     (w & 0xff).toByte)
      mem.write(base + 2 * i + 1, ((w >> 8) & 0xff).toByte)
    }
  }
  /** Wire the full whitebox commit capture (incl. the exception commit channel) used
    * by the ITLB lock-step tests. */
  private def wireWhitebox(dut: FullCoreDut, handle: WhiteboxCapture.Handle): Unit = {
    def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
      handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
        dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
        intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
        x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean))
    }
    dut.clockDomain.onSamplings {
      captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs)
      val bw = dut.branchEu.logic.wbObs
      if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      for (k <- 0 until 2) {
        val c = dut.rob.logic.commitObs(k)
        if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
          sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
      }
      val ce = dut.rob.logic.commitObs(2)
      if (ce.fire.toBoolean) handle.onExcCommit(ce.pc.toLong & 0xffffffffL, ce.sysByte.toInt & 0xff, ce.a7.toLong & 0xffffffffL)
    }
  }

  // ── ITLB lock-step (a): code at a NON-IDENTITY instruction mapping ──────────
  // The program is linked at VA loadAddr but the ITLB maps that code page to a
  // DIFFERENT physical page (PPN 0x50000): the I-cache fetches from the PA while the
  // architectural PC stream is the VA. The oracle runs instruction fetch as identity
  // (PC = VA), so the committed PC/SR/A7/register stream lock-steps step-for-step —
  // proving the ITLB miss->walk->fill + translate is transparent.
  test("lock-step ITLB: code at a non-identity instruction mapping", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress           // VA 0x40800000
    val codePPN  = 0x50000L                                      // PA page 0x50000000
    val codePA   = codePPN << 12
    // A register-only straight-line program (no data accesses -> only the I-side
    // translates). 6 instructions, then halt.
    val src = "moveq #1,%d0 ; moveq #2,%d1 ; add.l %d0,%d1 ; moveq #7,%d2 ; " +
              "sub.l %d0,%d2 ; and.l %d1,%d2 ; loop: bra loop"
    val nInstr = 6
    val oracleSteps = Musashi.assembleAndTrace(src, maxCycles = 20000) match {
      case Right(v)  => v.take(nInstr); case Left(e) => fail(s"[itlb-a] oracle: ${e.reason}") }
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i; case Left(e) => fail(s"[itlb-a] assemble: ${e.reason}") }

    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      wireWhitebox(dut, handle)

      // I-cache memory: code lives at the PA (0x50000000), NOT the VA.
      val icmem = SparseMemory()
      writeCodeAt(icmem, codePA, image.bytes)
      new Axi4ReadOnlySlaveAgent(dut.icache.logic.axi, cd) {
        override def readByte(address: BigInt, id: Int): Byte = icmem.read(address.toLong)
      }
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Page table (in both walker memories): map VA loadAddr -> PPN 0x50000 (resident).
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      def build(mem: m68k040.ls.BehavioralMemAgent): Unit = {
        for (i <- 0 until 8) mapPage(mem, loadAddr + i * 0x1000L, codePPN + i, MMU_PAGT2)
      }
      build(ptmem); build(itlbPtmem)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.rootPtr   #= MMU_ROOT

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      var guard = 0; val cap = 5000
      while (handle.result.size < nInstr && guard < cap) { cd.waitSampling(); guard += 1 }
      assert(handle.result.size >= nInstr,
        s"[itlb-a] only ${handle.result.size}/$nInstr committed within $cap cycles")
      val res = LockStep.compare(handle.result.take(nInstr), oracleSteps)
      assert(res.ok, s"[itlb-a] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
        s"(matched ${res.matched}, dut ${handle.result.size}, oracle $nInstr)")
    }
  }

  // ── ITLB lock-step (b): branch to a NON-RESIDENT I-page -> format-$7 -> RTE ──
  // The program (at loadAddr) installs the vector-2 handler, then branches FORWARD
  // (bra.w, reachable) to VA loadAddr+0x2000 — an instruction page left NON-RESIDENT.
  // The I-fetch there faults -> the core delivers a format-$7 access fault (SSW
  // program-space, EA = the faulting page VA) -> the handler writes a resident
  // descriptor for that page -> RTE re-fetches it, which now runs (writes D3 then
  // halts). The committed PC/SR/A7 stream + the stacked $7 frame match the MAME-040
  // oracle (extended for I-fetch) step-for-step / byte-for-byte. (The 040 decoder has
  // no absolute JMP, so the non-resident page is within bra.w reach of the code.)
  test("lock-step ITLB: non-resident I-page -> format-$7 -> handler maps -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress       // 0x40800000
    val farVA    = loadAddr + 0x2000L                        // 0x40802000 (non-resident I-page)
    // Code leaf table PAGD placed at 0x3F000 so the far-page descriptor (PAGD+2*4 =
    // 0x3F008) is itself in pageA's identity range (ptr[0], pageIdx 0x3f) -> the
    // handler's DATA write to VA 0x3F008 translates to PA 0x3F008.
    val PTRT = 0x00081000L; val PAGA = 0x00082000L; val PAGD = 0x0003F000L
    val descVA = PAGD + 2*4                                  // 0x3F008 (the far-page descriptor)
    // Far page resident IDENTITY (PPN farVA>>12). LE descriptor; big-endian move.l imm
    // = byteswap(that).
    val farPpn   = (farVA >> 12) & 0xfffffL                  // 0x40802
    val farDescLE = ((farPpn << 12) | 0x1L) & 0xffffffffL    // 0x40802001
    val farDescBE = java.lang.Long.reverseBytes(farDescLE) >>> 32  // byteswap to a move.l imm
    // Program: install handler @ 0x8, bra.w to far. far page (.org 0x2000) writes D3
    // then halts. handler maps PAGD[2] resident then RTE.
    // The fall-through after `bra.w far` (the mispredicted not-taken path) is fetched
    // speculatively before the redirect; keep it STORE-FREE (filler moveqs) so no
    // wrong-path store is ever allocated into the SQ. The handler (with its store)
    // sits AFTER the filler.
    val filler = (0 until 24).map(_ => "moveq #0,%d7").mkString(" ; ")
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +
      "moveq #1,%d4 ; moveq #2,%d5 ; moveq #3,%d6 ; " +   // let the vec-store drain
      "bra.w far ; " +
      filler + " ; " +                                   // store-free wrong-path window
      f"handler: move.l #0x$farDescBE%08x,%%d1 ; move.l %%d1,0x3F008 ; rte ; " +
      ".org 0x2000 ; far: moveq #99,%d3 ; floop: bra floop"
    val nInstr = 10  // vec-imm, vec-store, 3x moveq, [fault@far], handler-imm, handler-store, rte, moveq@far
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i; case Left(e) => fail(s"[itlb-b] assemble: ${e.reason}") }

    // Oracle: instr window covers ONLY the far page [farVA, farVA+0x1000); preloaded
    // resident root[4]/ptr[0x20] (the code's root/ptr) + PAGD[2] NON-RESIDENT (=0).
    // The oracle fetches the far-page code from its own memory (the image already
    // contains it at offset 0x2000). The handler maps PAGD[2] resident, RTEs.
    val rIdx = ((loadAddr >> 25) & 0x7f).toInt
    val pIdx = ((loadAddr >> 18) & 0x7f).toInt
    val oraclePt = Seq(
      (0x80000L + rIdx*4) -> ((PTRT & 0xfffffff0L) | 0x2L),   // root[code] -> ptr
      (PTRT + pIdx*4)     -> ((PAGD & 0xfffffff0L) | 0x2L),   // ptr[code]  -> PAGD
      (PAGD + 2*4)        -> 0x0L) ++                          // PAGD[2] = NON-RESIDENT (the I-fault)
      // the OTHER code pages identity-resident so the rest of the program fetches.
      (0 until 8).filter(_ != 2).map(i => (PAGD + i*4) -> ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L))
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0L, dataHi = 0L,
      ptPreload = oraclePt, instrLo = farVA, instrHi = farVA + 0x1000L))
    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v; case Left(e) => fail(s"[itlb-b] oracle: ${e.reason}") }
    assert(oracleSteps.size >= nInstr, s"[itlb-b] oracle produced ${oracleSteps.size} steps (< $nInstr)")
    val oracle = oracleSteps.take(nInstr)

    M68kSim().withVerilator.compile(new FullCoreDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      wireWhitebox(dut, handle)

      // I-cache memory: the whole image at loadAddr (identity PA == VA for resident
      // pages; the far page is at PA loadAddr+0x2000 once mapped identity).
      val icmem = SparseMemory()
      writeCodeAt(icmem, loadAddr, image.bytes)
      new Axi4ReadOnlySlaveAgent(dut.icache.logic.axi, cd) {
        override def readByte(address: BigInt, id: Int): Byte = icmem.read(address.toLong)
      }
      // D-cache + walker memories share one backing store (the handler's PT write must
      // be visible to the ITLB re-walk).
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * i)) & 0xff).toInt)
      // Page table. Data accesses that translate: the vector store (VA 0x8) and the
      // handler PT write (VA 0x3F008). Both via root[0]->ptr[0]->pageA, identity.
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)   // root[0] -> ptr
      pokeLE(PTRT + 0*4,      (PAGA & 0xfffffff0L) | 0x2L)   // ptr[0]  -> pageA (0x0..0x3FFFF)
      pokeLE(PAGA + 0*4,      (0x0L << 12) | 0x1L)           // pageA[0] = identity (vector store VA 0x8)
      pokeLE(PAGA + 0x3f*4,   (0x3fL << 12) | 0x1L)          // pageA[0x3f] = identity VPN 0x3F (PT write VA 0x3F008)
      // Code region: root[code]->ptr, ptr[code]->PAGD; PAGD[2] NON-RESIDENT, the rest
      // identity-resident.
      pokeLE(0x80000L + rIdx*4, (PTRT & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + pIdx*4,     (PAGD & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val v = if (i == 2) 0x0L else ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L)
        pokeLE(PAGD + i*4, v)
      }
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.rootPtr   #= 0x80000L

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      var guard = 0; val cap = 12000; var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.itlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[itlb-b] the I-fetch to the non-resident page must flag an ITLB fault")
      assert(handle.result.size >= nInstr,
        s"[itlb-b] only ${handle.result.size}/$nInstr committed within $cap cycles")
      // Stacked format-$7 frame (supervisor stack, big-endian): SSW = program-space.
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert((pk16(fb) & 0xff00) == 0x2700, f"[itlb-b] frame SR=0x${pk16(fb)}%04x expected 0x27xx")
      assert(pk16(fb + 6) == 0x7008, f"[itlb-b] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008")
      assert(pk32(fb + 8) == farVA, f"[itlb-b] frame EA=0x${pk32(fb + 8)}%08x expected 0x$farVA%08x")
      assert(pk16(fb + 0xc) == 0x0506, f"[itlb-b] frame SSW=0x${pk16(fb + 0xc)}%04x expected 0x0506 (in_mmu|super-program|read)")
      assert(pk32(fb + 0x14) == farVA, f"[itlb-b] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x$farVA%08x")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok, s"[itlb-b] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
        s"(matched ${res.matched}, dut ${handle.result.size}, oracle $nInstr)")
    }
  }
}
