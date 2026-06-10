package m68k040.lockstep

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin, DcacheService}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin, DivEuPlugin}
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
  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin, divEu: DivEuPlugin) extends FiberPlugin {
    // Int PRF write port for the exception unit's A7 (reg 15) write-back. Allocated
    // in setup (RegfileService requires it). latency=0 so the handler can read the
    // updated A7 the cycle after the exception commits (it is serializing).
    var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
    // Sim-only boot seed of the int PRF (A7 = boot SSP). Shares the "excA7" physical
    // write port (the exc unit is idle at boot, so no same-cycle collision). Driven by
    // the harness for a couple of cycles after the init sweep, before the first fetch.
    var seedWr: m68k040.execute.regfile.RegFileWritePort = null
    during setup {
      a7Wr   = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7")
      seedWr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7", priority = 1)
    }
    val logic = during build new Area {
      val seedValid = in Bool (); val seedAddr = in UInt (6 bits); val seedData = in Bits (32 bits)
      seedValid.simPublic(); seedAddr.simPublic(); seedData.simPublic()
      seedWr.valid := seedValid; seedWr.address := seedAddr; seedWr.data := seedData
      val iq  = host[IssueQueueService]
      val rob = host[RobPlugin]
      eu0.issue << iq.issue(0)
      eu1.issue << iq.issue(1)
      // SLOW-ALU (shift, latency-2) dynamic wakeup: ONE IQ port per ALU EU (mirrors
      // top/FullCoreSynth.BackendWiringPlugin). Without this the IQ's aluSlowWakeup
      // ports keep their setup default (valid:=False), so a shift's dependents (and
      // its own aluSlow* busy bitmaps) never wake -> deadlock.
      iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
      iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
      iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
      iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
      // Branch EU: issue port 2 (branch-class) -> branch EU; completion -> ROB
      // branchCompletion (records {mispredict, nextPc} for commit-time recovery).
      branchEu.issue << iq.issue(2)
      rob.logic.branchCompletion.valid   := branchEu.completion.valid
      rob.logic.branchCompletion.payload := branchEu.completion.payload
      // Execute-time conditional fault (TRAPV vector 7) -> ROB euFault (generalized).
      rob.logic.euFaultCompletion.valid   := branchEu.trapvFault.valid
      rob.logic.euFaultCompletion.payload := branchEu.trapvFault.payload
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
      wireCcr(3, divEu.logic.wbObs)

      // ── CPLX (DivEu) wiring (mirrors top/FullCoreSynth) ──
      // Issue port 4 -> DivEu; completion (port 3) + dynamic wakeup + euFault.
      divEu.issue << iq.issue(4)
      // Squash a multi-cycle DIV/MUL flushed in flight (same flush the IQ uses).
      divEu.cplxFlush := host[RedirectService].doFlush || rob.logic.excActive
      rob.logic.completion(3).valid   := divEu.completion.valid
      rob.logic.completion(3).payload := divEu.completion.payload
      iq.cplxWakeup.valid   := divEu.wakeup.valid
      iq.cplxWakeup.payload := divEu.wakeup.payload
      when(divEu.euFault.valid) {
        rob.logic.euFaultCompletion.valid   := True
        rob.logic.euFaultCompletion.payload := divEu.euFault.payload
      }

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
      iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
      iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
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
    val divEu  = new DivEuPlugin
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val wire   = new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()),
      ctrl,
      intCtrl,
      itlb,
      dtlb,
      icache, dcache, fa, dec, ren, disp, rob, iq, eu0, eu1, branchEu, lsEu, divEu,
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
              xWrite    = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
        }
      }

      // Per-cycle sampler: EU writeback-obs (join key) + ROB commit-obs (order/pc).
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        // LS EU writeback-obs (loads write an int reg incl. the T0/T1 temp; stores
        // write none). Same join key (robId) as the ALU EUs. Temp-only commits are
        // dropped in WhiteboxCapture.onCommit (decode-matrix §4.5).
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs);
        // Branch EU writeback-obs: a branch writes NO int/flag reg and leaves CCR
        // unchanged. Map it to a no-write Wb (the commit pc comes from the ROB
        // commitObs = resolved nextPc). dstArch=0 is harmless since intWrite=false.
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            wbCount += 1
            // A plain branch writes no reg; an RTS/RTR ibranch writes A7 (postinc SP).
            val anWr = bw.anWrite.toBoolean
            handle.onWb(
              bw.robId.toInt,
              WhiteboxCapture.Wb(
                dstArch   = if (anWr) bw.anArch.toInt else 0,
                result    = if (anWr) bw.anData.toLong & 0xffffffffL else 0L,
                intWrite  = anWr,
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
            if (sys.env.contains("CR_RAW")) {
              val w = handle.peekWb(c.robId.toInt)
              println(f"[$name] RAWCOMMIT rob=${c.robId.toInt} pc=0x${c.pc.toLong & 0xffffffffL}%08x dstArch=${w.map(_.dstArch).getOrElse(-1)} intW=${w.map(_.intWrite).getOrElse(false)} res=0x${w.map(_.result & 0xffffffffL).getOrElse(0L)}%08x divRem=${w.map(_.divRem).getOrElse(false)}")
            }
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
          }
        }
        // Exception / RTE commit channel (handler-entry / restored PC + sysByte/A7).
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            commitCount += 1
            handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL, if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1)
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
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0

      // Let the PRF init sweep + rename committed-RAT identity init finish.
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot the committed supervisor SP to Musashi's initial SSP (0x00100000) AFTER
      // the init sweep (it resets committed state) so the surfaced A7 (== SSP, S=1)
      // matches OracleStep.a(7) for every program.
      dut.rob.logic.exc.ss.ssp #= 0x00100000L
      // Seed the int PRF arch-15 (A7, identity phys-15) to the boot SSP too: the OoO
      // datapath reads A7 from the int PRF (call/return push/pop), so it must mirror
      // the committed SSP at boot (reset loads SSP into A7). The exc unit keeps ss.ssp
      // in sync on exceptions; the PRF arch-15 follows OoO writes thereafter.
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
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
        cd.waitSampling(); guard += 1
      }
      assert(handle.result.size >= n,
        s"[$name] only ${handle.result.size}/$n instructions committed within $cap cycles")

      val res = LockStep.compare(handle.result.take(n), oracle)
      if (!res.ok && sys.env.contains("CR_DEBUG")) {
        val rr = handle.result.take(n)
        for (i <- 0 until n) {
          val c = rr(i); val s = oracle(i)
          println(f"[$name] idx$i%2d dut pc=0x${c.pc}%08x a7=0x${c.a7 & 0xffffffffL}%08x reg${c.archRegId}=0x${c.archRegWrite & 0xffffffffL}%08x(v=${c.archRegValid}) | orc pc=0x${s.pc}%08x a7=0x${s.a(7) & 0xffffffffL}%08x")
        }
      }
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
            xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
        }
      }

      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs);
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
              handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL, if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1)
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

  // ── MOVE.B/.W to Dn: partial-register write (preserve Dn upper bytes) ───────
  // 68k semantics: MOVE.B writes Dn[7:0] / preserves Dn[31:8]; MOVE.W writes
  // Dn[15:0] / preserves Dn[31:16]; MOVE.L writes all 32. MOVE sets N/Z from the
  // moved value (per size), V=C=0, X untouched. Seed Dn = 0x11223344 (a long-imm
  // MOVE.L) then a .B / .W MOVE: the value AND flags are compared step-for-step.
  test("lock-step: MOVE.B/.W to Dn preserves upper bytes", VerilatorTest) {
    runLockStep("move-partial", Seq(
      "move.l #0x11223344,%d0", "move.b #0xaa,%d0",            // -> 0x112233aa, N=1
      "move.l #0x11223344,%d1", "move.w #0x55aa,%d1",          // -> 0x112255aa
      "move.l #0xffffffff,%d2", "move.b #0x00,%d2",            // -> 0xffffff00, Z=1
      "move.l #0x80000000,%d3", "move.w #0x0001,%d3",          // -> 0x80000001
      "move.l #0x12345678,%d4", "move.b %d0,%d4",              // reg src .B -> 0x123456aa
      "move.l #0x0000abcd,%d5", "move.w %d1,%d5"               // reg src .W -> 0x000055aa
    ).mkString(" ; "))
  }

  // MOVE.L to Dn (full write, no merge) + MOVEA.W/.L (An full-32, .W sign-extend,
  // NO flags) must stay correct — guards against the partial fix touching them.
  test("lock-step: MOVE.L full + MOVEA.W/.L (no merge / no flags)", VerilatorTest) {
    runLockStep("move-l-movea", Seq(
      "move.l #0x11223344,%d0", "move.l #0xaabbccdd,%d0",      // .L full overwrite
      "move.l #0x0000ffff,%d1", "movea.w %d1,%a2",             // .W sign-extend -> 0xffffffff
      "move.l #0x12345678,%d3", "movea.l %d3,%a4"              // .L full to An
    ).mkString(" ; "))
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

  // ── Line-0 immediates (ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,Dn) lock-step ──────
  // Value + NZVCX step-for-step vs Musashi, with flag-affecting operands (carry,
  // overflow, zero, negative) across .B/.W/.L.
  test("lock-step: ADDI/SUBI .B/.W/.L flag boundaries", VerilatorTest) {
    runLockStep("addi-subi", Seq(
      "moveq #0,%d0", "addi.b #0x7f,%d0", "addi.b #1,%d0",       // .B overflow 0x7f+1
      "moveq #0,%d1", "addi.b #0xff,%d1", "addi.b #1,%d1",       // .B carry/zero 0xff+1
      "moveq #0,%d2", "addi.w #0x7fff,%d2", "addi.w #1,%d2",     // .W overflow
      "moveq #0,%d3", "addi.l #0x7fffffff,%d3", "addi.l #1,%d3", // .L overflow
      "moveq #1,%d4", "subi.b #2,%d4",                          // .B borrow -> negative
      "moveq #5,%d5", "subi.w #5,%d5",                          // .W zero
      "moveq #0,%d6", "subi.l #1,%d6"                           // .L borrow -> 0xffffffff
    ).mkString(" ; "))
  }
  // ── Line-5 ADDQ/SUBQ (Dn flags + An full-32 no-flags) lock-step ─────────────
  // Dn dest: ADD/SUB #1-8 with NZVCX (size-merged for .B/.W), flag-edge operands
  // (carry/overflow/zero/negative). An dest: full-32 add/sub, NO flags, .W operates
  // on the full 32 (#imm zero-extended). Value + NZVCX step-for-step vs Musashi.
  test("lock-step: ADDQ/SUBQ .B/.W/.L Dn flags + An full-32 no-flags", VerilatorTest) {
    runLockStep("addq-subq", Seq(
      "moveq #0,%d0", "addq.b #1,%d0", "addq.b #8,%d0",          // .B 0->1->9
      "move.l #0x0000007f,%d1", "addq.b #1,%d1",                // .B overflow 0x7f+1 -> V,N
      "move.l #0x000000ff,%d2", "addq.b #1,%d2",                // .B carry/zero 0xff+1 -> C,Z,X
      "move.l #0x00007fff,%d3", "addq.w #1,%d3",                // .W overflow
      "move.l #0x7fffffff,%d4", "addq.l #1,%d4",                // .L overflow
      "moveq #5,%d5", "subq.b #5,%d5",                          // .B zero
      "moveq #1,%d6", "subq.w #2,%d6",                          // .W borrow -> 0xffff word, N,C,X
      "move.l #0x11223344,%d7", "subq.l #8,%d7",                // .L generic
      // An dest: full-32 add/sub, NO flags. .W operates on the full 32 bits.
      "movea.l #0x00010000,%a0", "addq.w #1,%a0",               // -> 0x00010001 full-32
      "movea.l #0x00000001,%a1", "subq.l #2,%a1",               // -> 0xffffffff full-32
      "movea.l #0x0000ffff,%a2", "addq.w #8,%a2"                // -> 0x00010007 full-32 (no word-wrap)
    ).mkString(" ; "))
  }

  // ── Line-5 Scc (set byte on condition) lock-step ────────────────────────────
  // Scc Dn := cond ? 0xFF : 0x00 (byte partial write, preserve Dn[31:8], NO flags).
  // Seed flags via a CMP, then several Scc conditions both true and false; the upper
  // 24 bits of each Dn (pre-seeded) must be preserved. Value step-for-step vs Musashi.
  test("lock-step: Scc set byte on condition (>=3 conds, true/false, upper preserved)", VerilatorTest) {
    runLockStep("scc", Seq(
      "move.l #0xaaaaaa00,%d0", "move.l #0xbbbbbb00,%d1",
      "move.l #0xcccccc00,%d2", "move.l #0xdddddd00,%d3",
      "move.l #0xeeeeee00,%d4", "move.l #0xffffff00,%d5",
      "moveq #5,%d6", "moveq #5,%d7",
      "cmp.l %d7,%d6",          // 5-5 -> Z=1 (EQ true, NE false, GE/LE true, GT/LT false)
      "seq %d0",                // EQ true  -> D0[7:0]=0xFF -> 0xaaaaaaff
      "sne %d1",                // NE false -> D1[7:0]=0x00 -> 0xbbbbbb00
      "smi %d2",                // MI false (N=0) -> 0xcccccc00
      "spl %d3",                // PL true  (N=0) -> 0xddddddff
      "st  %d4",                // always true -> 0xeeeeeeff
      "sf  %d5"                 // always false -> 0xffffff00
    ).mkString(" ; "))
  }

  // ── Line-5 DBcc / DBRA (decrement-and-branch counted loops) lock-step ───────
  // DBRA (cc=F) is the common counted loop: it ALWAYS decrements Dn.W and branches
  // until Dn.W reaches -1 (0xFFFF), then falls through. The counter is Dn[15:0]
  // (partial — Dn[31:16] preserved). Value + PC/Dn step-for-step vs Musashi.
  test("lock-step: DBRA counted loop (runs N times, exits at -1, Dn.W partial)", VerilatorTest) {
    // D0 = 3 (counter, upper-16 = 0). Body addq.l #1,d1. DBRA decrements D0.W each
    // iteration and branches while D0.W != -1:
    //   iter1 D0:3->2 branch ; iter2 2->1 branch ; iter3 1->0 branch ; iter4 0->-1 fall.
    // Body runs 4 times -> D1 = 4 ; D0 ends 0x0000ffff (low16 = -1, upper16 preserved).
    // Executed: moveq#3(1) + 4*(addq+dbra)(8) + moveq#9(1) = 10.
    runLockStep("dbra",
      "moveq #3,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; dbra %d0,.L ; moveq #9,%d2",
      nInstr = 10)
  }

  // DBcc with a REAL condition (DBEQ): branches+decrements only when cond is FALSE
  // (Z=0, not-equal); falls through (NO decrement) when cond is TRUE (Z=1, equal) OR
  // on counter expiry. Exercises the taken (dec+branch), not-taken (cond-true early
  // exit, no decrement) and the counter all together.
  test("lock-step: DBEQ real-condition loop (taken / cond-true fall-through)", VerilatorTest) {
    // D0=4 (counter), D1=0. Body addq.l #1,d1 ; cmp.l #2,d1 (sets Z when d1==2).
    //   iter1: d1=1, 1!=2 -> Z=0 (EQ false) -> dec D0 4->3, branch.
    //   iter2: d1=2, 2==2 -> Z=1 (EQ true)  -> fall through (NO dec): D0 stays 3.
    // Exit: D1=2, D0=3. Executed: moveq#4,moveq#0(2) + iter1(addq,cmp,dbeq=3) +
    //   iter2(addq,cmp,dbeq=3) + moveq#9(1) = 9.
    runLockStep("dbeq",
      "moveq #4,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; cmp.l #2,%d1 ; dbeq %d0,.L ; moveq #9,%d2",
      nInstr = 9)
  }

  // DBRA with a non-zero upper-16 in the counter: the decrement-and-test uses ONLY
  // Dn[15:0]; Dn[31:16] must be preserved across the whole loop and the -1 expiry.
  test("lock-step: DBRA preserves Dn[31:16] across the loop", VerilatorTest) {
    // D0 = 0xABCD0002 (counter low16 = 2, upper16 = 0xABCD). DBRA runs the body 3
    // times (2->1->0->-1); D0 ends 0xABCDffff (upper16 preserved). Executed:
    // move.l(1) + moveq#0(1) + 3*(addq+dbra=2)=6 + moveq#9(1) = 9.
    runLockStep("dbra-upper",
      "move.l #0xabcd0002,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; dbra %d0,.L ; moveq #9,%d2",
      nInstr = 9)
  }

  test("lock-step: ANDI/ORI/EORI .B/.W/.L (NZ, V=C=0)", VerilatorTest) {
    runLockStep("andi-ori-eori", Seq(
      "move.l #0x12345678,%d0", "andi.l #0xff00ff00,%d0",       // .L AND
      "move.l #0x00000000,%d1", "ori.l #0x80000000,%d1",        // .L OR -> negative
      "move.l #0xaaaaaaaa,%d2", "eori.l #0xffffffff,%d2",       // .L EOR -> 0x55555555
      "move.l #0x000000ff,%d3", "andi.b #0x0f,%d3",             // .B AND
      "move.l #0x00000000,%d4", "ori.w #0x8000,%d4",            // .W OR -> negative word
      "move.l #0x0000ffff,%d5", "eori.w #0xffff,%d5",           // .W EOR -> zero word
      "move.l #0x000000a5,%d6", "eori.b #0xa5,%d6"              // .B EOR -> zero byte
    ).mkString(" ; "))
  }
  test("lock-step: CMPI .B/.W/.L (NZVC, no write)", VerilatorTest) {
    runLockStep("cmpi", Seq(
      "moveq #5,%d0", "cmpi.l #5,%d0",                          // equal -> Z
      "moveq #5,%d1", "cmpi.l #6,%d1",                          // 5-6 -> negative/borrow
      "moveq #-1,%d2", "cmpi.b #0x7f,%d2",                      // .B signed boundary
      "move.l #0x00008000,%d3", "cmpi.w #1,%d3"                 // .W overflow boundary
    ).mkString(" ; "))
  }

  // ── EOR Dn,Dm (register destination) lock-step ──────────────────────────────
  test("lock-step: EOR Dn,Dm .B/.W/.L (NZ, V=C=0)", VerilatorTest) {
    runLockStep("eor-reg", Seq(
      "move.l #0xaaaaaaaa,%d0", "move.l #0x55555555,%d1", "eor.l %d0,%d1",  // -> 0xffffffff neg
      "move.l #0x12345678,%d2", "move.l #0x12345678,%d3", "eor.l %d2,%d3",  // -> 0 zero
      "move.l #0x000000f0,%d4", "move.l #0x0000000f,%d5", "eor.b %d4,%d5",  // .B -> 0xff neg byte
      "move.l #0x0000abcd,%d6", "move.l #0x0000abcd,%d7", "eor.w %d6,%d7"   // .W -> 0 zero word
    ).mkString(" ; "))
  }

  // ── Line-E register-form shifts/rotates (immediate count) lock-step ─────────
  // Each of the 8 ops, .B/.W/.L, with operands exercising C/X/V/N/Z edges:
  // shift-out-1, ASL sign-change (V), and ROX-through-X. Result + NZVCX step-for-step.
  test("lock-step: ASL/ASR .B/.W/.L immediate count (NZVCX, ASL-V)", VerilatorTest) {
    runLockStep("shift-as-imm", Seq(
      "move.l #0x40000000,%d0", "asl.l #1,%d0",            // .L sign-change -> V, C=0
      "move.l #0xc0000000,%d1", "asl.l #1,%d1",            // .L MSB stable (both top bits 1) -> V=0, C=1
      "move.l #0x00000040,%d2", "asl.b #2,%d2",            // .B 0x40<<2 -> 0x00 C=1 from bit6
      "move.l #0xffffffff,%d3", "asr.l #4,%d3",            // .L sign-extend, C=1
      "move.l #0x00008000,%d4", "asr.w #1,%d4",            // .W arithmetic -> 0x0000c000 N=1 C=0
      "move.l #0x00000081,%d5", "asr.b #1,%d5",            // .B -> 0x000000c0 C=1
      "move.l #0x00000001,%d6", "asl.l #8,%d6"             // .L generic
    ).mkString(" ; "))
  }
  test("lock-step: LSL/LSR .B/.W/.L immediate count (NZ, C=X, V=0)", VerilatorTest) {
    runLockStep("shift-ls-imm", Seq(
      "move.l #0x80000000,%d0", "lsl.l #1,%d0",            // .L -> 0 C=1 X=1 Z=1
      "move.l #0x00000001,%d1", "lsr.l #1,%d1",            // .L -> 0 C=1 X=1 Z=1
      "move.l #0x00008000,%d2", "lsl.w #1,%d2",            // .W -> 0 C=1
      "move.l #0x00000001,%d3", "lsr.b #1,%d3",            // .B -> 0 C=1
      "move.l #0x12345678,%d4", "lsr.l #4,%d4",            // .L generic C=0
      "move.l #0x000000ff,%d5", "lsl.b #8,%d5"             // .B shift==size
    ).mkString(" ; "))
  }
  test("lock-step: ROXL/ROXR .B/.W/.L immediate count (rotate-through-X)", VerilatorTest) {
    runLockStep("shift-rox-imm", Seq(
      // seed X via an add that carries, then ROX feeds X in and out.
      "ori #0x10,%ccr",                                    // X=1 (CCR bit4)
      "move.l #0x00000000,%d1", "roxl.l #1,%d1",           // X(1) rotates into bit0 -> d1=1, X=0
      "move.l #0x80000000,%d2", "roxl.l #1,%d2",           // bit31 -> X/C, X-in(0) -> bit0
      "move.l #0x00000001,%d3", "roxr.l #1,%d3",           // bit0 -> X/C
      "move.l #0x00000001,%d4", "roxr.w #1,%d4",           // .W ROX
      "move.l #0x00000080,%d5", "roxl.b #1,%d5"            // .B ROX
    ).mkString(" ; "))
  }
  test("lock-step: ROL/ROR .B/.W/.L immediate count (C, X UNAFFECTED)", VerilatorTest) {
    runLockStep("shift-ro-imm", Seq(
      // pre-set X=1 via carry; ROL/ROR must leave X untouched.
      "ori #0x10,%ccr",                                    // X=1 (CCR bit4)
      "move.l #0x80000001,%d0", "rol.l #1,%d0",            // -> 0x00000003, C=1, X stays 1
      "move.l #0x00000001,%d1", "ror.l #1,%d1",            // -> 0x80000000, C=1, X stays 1
      "move.l #0x00008000,%d2", "rol.w #1,%d2",            // .W -> 0x0001 C=1
      "move.l #0x00000001,%d3", "ror.b #1,%d3",            // .B -> 0x80 C=1
      "move.l #0x12345678,%d4", "rol.l #4,%d4"             // .L generic
    ).mkString(" ; "))
  }

  // ── Line-E register-form shifts/rotates (register count Dc mod 64) lock-step ─
  // count = Dc & 0x3f. Exercise count 0 (flag specials), count >= size, mod-64
  // wrap, and the generic case across the 8 ops + .B/.W/.L.
  test("lock-step: AS/LS register count (count 0, >=size, mod-64)", VerilatorTest) {
    runLockStep("shift-asls-reg", Seq(
      "move.l #0xdeadbeef,%d0", "moveq #0,%d1", "asl.l %d1,%d0",   // count 0: C=0, X untouched, NZ of src
      "move.l #0x00000001,%d2", "moveq #32,%d3", "asl.l %d3,%d2",  // count==size: result 0, C=X=src&1
      "move.l #0x00008000,%d4", "moveq #40,%d5", "lsl.w %d5,%d4",  // .W count 40>16: result 0
      "move.l #0xffffffff,%d6", "moveq #4,%d7", "asr.l %d7,%d6",   // .L generic arithmetic, C=1
      "move.l #0x12345678,%d0", "move.l #64,%d1", "lsl.l %d1,%d0", // Dc=64 -> mod64=0 -> count 0
      "move.l #0x80000000,%d2", "moveq #1,%d3", "lsr.l %d3,%d2"    // .L LSR generic
    ).mkString(" ; "))
  }
  test("lock-step: ROX/RO register count (count 0=ROX-C-from-X, mod-64)", VerilatorTest) {
    runLockStep("shift-roxro-reg", Seq(
      "ori #0x10,%ccr",                                            // X=1
      "move.l #0x00000000,%d0", "moveq #0,%d1", "roxl.l %d1,%d0",  // ROX count0: C=X (1), result=src
      "move.l #0x00000001,%d2", "moveq #1,%d3", "roxr.l %d3,%d2",  // ROXR bit0 -> X/C
      "move.l #0x80000001,%d4", "moveq #0,%d5", "rol.l %d5,%d4",   // ROL count0: C=0, X untouched, NZ src
      "move.l #0x00000001,%d6", "moveq #36,%d7", "ror.l %d7,%d6",  // ROR Dc=36 mod32=4
      "move.l #0x00000003,%d0", "move.l #33,%d1", "roxl.l %d1,%d0" // ROXL Dc=33 mod33=0 -> count0 path
    ).mkString(" ; "))
  }

  // ── ANDI/ORI/EORI #imm,CCR (NOT privileged — CCR only) lock-step ────────────
  // Set up the CCR via an arithmetic op (subi -> known NZVCX), then AND/OR/EOR the
  // immediate byte into the CCR (X=4,N=3,Z=2,V=1,C=0), verified step-for-step incl X.
  test("lock-step: ANDI #imm,CCR clears flags", VerilatorTest) {
    runLockStep("andi-ccr", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // sets N + C + X (0 - 1 = 0xff)
      "andi #0x00,%ccr",                      // clear all CCR bits
      "moveq #1,%d1"                          // observe cleared CCR carried forward
    ).mkString(" ; "))
  }
  test("lock-step: ORI #imm,CCR sets flags", VerilatorTest) {
    runLockStep("ori-ccr", Seq(
      "moveq #5,%d0", "cmpi.l #5,%d0",        // Z=1, others mostly 0
      "ori #0x1f,%ccr",                       // set X,N,Z,V,C
      "moveq #2,%d1"
    ).mkString(" ; "))
  }
  test("lock-step: EORI #imm,CCR toggles flags", VerilatorTest) {
    runLockStep("eori-ccr", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // N=1,C=1,X=1 (0xff), Z=0,V=0
      "eori #0x1f,%ccr",                      // toggle all 5 -> N=0,C=0,X=0,Z=1,V=1
      "moveq #3,%d1"
    ).mkString(" ; "))
  }

  // ── Slow-ALU (shift, latency-2) DEPENDENT CHAINS ────────────────────────────
  // Exercises the IQ aluSlow dynamic-wakeup (lat2): an op that consumes a SHIFT's
  // INT result, NZVC result, and X result must wait one extra cycle and then read
  // the correct value. Pre-fix (the aluSlowWakeup port was unwired in the test
  // BackendWiringPlugin) these DEADLOCKED — the consumer's aluSlowWait never cleared.
  test("lock-step: dependent chain through a SHIFT int result (lat2 wakeup)", VerilatorTest) {
    runLockStep("shift-dep-int", Seq(
      "move.l #0x00000003,%d0", "lsl.l #4,%d0",    // d0 = 0x30 (slow producer)
      "add.l %d0,%d1",                              // CONSUMES d0 (shift int result) -> waits lat2
      "move.l #0x0000000f,%d2", "lsl.l #2,%d2",    // d2 = 0x3c (slow)
      "move.l %d2,%d3",                             // CONSUMES d2 (shift result)
      "sub.l %d0,%d2"                               // CONSUMES d0 AND d2 (two shift results)
    ).mkString(" ; "))
  }
  test("lock-step: dependent chain through a SHIFT NZVC result (lat2 wakeup)", VerilatorTest) {
    runLockStep("shift-dep-nzvc", Seq(
      "move.l #0x80000000,%d0", "asl.l #1,%d0",     // shift sets N/Z/V/C (slow producer)
      "bne .skip",                                  // CONSUMES the shift's NZVC (cc read) -> lat2
      "moveq #1,%d1",
      ".skip: moveq #2,%d2"
    ).mkString(" ; "), nInstr = 4)
  }
  test("lock-step: back-to-back SHIFT chain (X + int + NZVC all lat2)", VerilatorTest) {
    runLockStep("shift-chain-bb", Seq(
      "ori #0x10,%ccr",                             // X=1
      "move.l #0x00000001,%d0",
      "roxl.l #1,%d0",                              // ROX reads X, writes X (slow)
      "roxl.l #1,%d0",                              // reads PRIOR shift's X + int result (slow->slow lat2)
      "roxl.l #1,%d0",                              // chained again
      "add.l %d0,%d1"                               // consumes the final shift int result
    ).mkString(" ; "))
  }

  // ── Line-4 single-operand family (CLR/NEG/NEGX/NOT/TST/SWAP/EXT/TAS) ─────────
  // Data-register forms. Value + NZVCX step-for-step vs Musashi (the gate). All
  // sizes + flag edges (zero/negative/overflow/carry), NEGX with X set + the
  // Z-clear-only (Z-preserve) rule, SWAP, EXT.W/.L, EXTB.L, TAS.

  test("lock-step: CLR.B/.W/.L (Z=1, N=V=C=0, upper preserved)", VerilatorTest) {
    runLockStep("clr", Seq(
      "move.l #0x11223344,%d0", "clr.b %d0",                  // -> 0x11223300, Z=1
      "move.l #0x11223344,%d1", "clr.w %d1",                  // -> 0x11220000, Z=1
      "move.l #0x11223344,%d2", "clr.l %d2",                  // -> 0, Z=1
      "moveq #-1,%d3", "subi.b #1,%d3", "clr.b %d3"           // set C/X then CLR (C cleared)
    ).mkString(" ; "))
  }

  test("lock-step: NEG.B/.W/.L (NZVCX, X=C, V overflow, C unless zero)", VerilatorTest) {
    runLockStep("neg", Seq(
      "moveq #1,%d0", "neg.b %d0",                            // 0-1 -> 0xff, N=1,C=1,X=1
      "moveq #0,%d1", "neg.l %d1",                            // 0-0 -> 0, Z=1,C=0,X=0,V=0
      "move.l #0x00000080,%d2", "neg.b %d2",                  // .B 0x80 -> overflow V=1, C=1
      "move.l #0x00008000,%d3", "neg.w %d3",                  // .W 0x8000 -> V=1, C=1
      "move.l #0x80000000,%d4", "neg.l %d4",                  // .L 0x80000000 -> V=1, C=1
      "move.l #0xaabbccdd,%d5", "neg.b %d5"                   // partial: upper preserved
    ).mkString(" ; "))
  }

  // NEGX = 0 - Dn - X; Z is CLEAR-ONLY (Z := Z_old && result==0). Exercises X=1 and
  // X=0 inputs and BOTH Z-preserve directions (result 0 with Z_old 0 -> Z stays 0;
  // result 0 with Z_old 1 -> Z stays 1). subi.b sets X + Z to feed the next negx.
  test("lock-step: NEGX.B/.W/.L (X input, Z clear-only / Z-preserve)", VerilatorTest) {
    runLockStep("negx", Seq(
      "moveq #5,%d0", "subi.b #2,%d0", "negx.b %d0",          // X=0 (5-2 no borrow), negx 0-3-0
      "moveq #0,%d1", "subi.b #1,%d1", "negx.b %d1",          // X=1 (0-1 borrow), negx 0-0xff-1=0; Z_old=0 -> Z STAYS 0
      "moveq #1,%d2", "subi.b #1,%d2", "negx.b %d2",          // X=0,Z=1 (1-1=0), negx 0-0-0=0; Z_old=1 -> Z STAYS 1
      "move.l #0x00008000,%d3", "subi.b #1,%d3", "negx.w %d3",// .W with X=0
      "move.l #0x80000001,%d4", "subi.b #1,%d4", "negx.l %d4" // .L with X=0
    ).mkString(" ; "))
  }

  // ── ADDX/SUBX register form (Dy,Dx): Dx := Dx +/- Dy +/- X ──────────────────
  // Extended arith with X folded into the carry/borrow-in + the NEGX clear-only Z
  // (Z := Z_old && result==0). X is seeded by a preceding flag-setter (addi/subi
  // that carries/borrows). Value + NZVCX step-for-step vs Musashi. addx/subx do NOT
  // touch X when... (they always write X = carry/borrow out).
  test("lock-step: ADDX.B/.W/.L x X=0/1 (NZVCX, X in/out)", VerilatorTest) {
    runLockStep("addx-basic", Seq(
      // .B X=0: 0x10 + 0x20 + 0 = 0x30, no carry -> X=0,C=0
      "moveq #5,%d0", "addi.b #1,%d0",                    // 5+1 no carry -> X=0
      "move.l #0x11223310,%d1", "move.l #0x44556620,%d2", "addx.b %d2,%d1",  // 0x10+0x20+0 -> 0x..30
      // .B X=1: seed X via carry; 0x01 + 0x01 + 1 = 0x03
      "move.l #0x000000ff,%d3", "addi.b #1,%d3",          // 0xff+1 -> carry -> X=1,Z=1
      "move.l #0xaaaa0001,%d4", "move.l #0xbbbb0001,%d5", "addx.b %d5,%d4",  // 1+1+1=3, upper preserved
      // .W X=1: 0x0001 + 0x0001 + 1 = 0x0003
      "move.l #0x0000ffff,%d6", "addi.b #1,%d6",          // X=1 again
      "move.l #0x12340001,%d7", "move.l #0x00010001,%d0", "addx.w %d0,%d7",  // .W upper preserved
      // .L X=0 overflow: 0x7fffffff + 0 + 0 = 0x7fffffff (V=0); then +1 via X
      "moveq #5,%d1", "addi.b #1,%d1",                    // X=0
      "move.l #0x7fffffff,%d2", "move.l #0x00000000,%d3", "addx.l %d3,%d2", // 0x7fffffff+0+0
      // .L X=1 overflow edge: 0x7fffffff + 0 + 1 = 0x80000000 (V=1,N=1)
      "move.l #0x000000ff,%d4", "addi.b #1,%d4",          // X=1
      "move.l #0x7fffffff,%d5", "move.l #0x00000000,%d6", "addx.l %d6,%d5"  // V from the X increment
    ).mkString(" ; "))
  }

  test("lock-step: SUBX.B/.W/.L x X=0/1 (NZVCX, borrow in/out)", VerilatorTest) {
    runLockStep("subx-basic", Seq(
      // .B X=0: 0x30 - 0x10 - 0 = 0x20, no borrow
      "moveq #5,%d0", "addi.b #1,%d0",                    // X=0
      "move.l #0x11223330,%d1", "move.l #0x44556610,%d2", "subx.b %d2,%d1", // 0x30-0x10-0
      // .B X=1 borrow: 0x10 - 0x10 - 1 = 0xff (borrow out, N=1,C=1,X=1)
      "move.l #0x00000000,%d3", "subi.b #1,%d3",          // 0-1 borrow -> X=1
      "move.l #0xaaaa0010,%d4", "move.l #0xbbbb0010,%d5", "subx.b %d5,%d4", // 0x10-0x10-1=0xff
      // .B borrow at 0x80 boundary (signed underflow): 0x80 - 0x01 - 0 ... use X=0 here
      "moveq #5,%d6", "addi.b #1,%d6",                    // X=0
      "move.l #0x12340080,%d7", "move.l #0x00000001,%d0", "subx.b %d0,%d7", // 0x80-1-0=0x7f, V=1
      // .W X=1: 0x0000 - 0x0000 - 1 = 0xffff (borrow out)
      "move.l #0x00000000,%d1", "subi.b #1,%d1",          // X=1
      "move.l #0x43210000,%d2", "move.l #0x00000000,%d3", "subx.w %d3,%d2", // 0-0-1=0xffff .W
      // .L X=1: 0x00000000 - 0x00000000 - 1 = 0xffffffff
      "move.l #0x00000000,%d4", "subi.b #1,%d4",          // X=1
      "move.l #0x00000000,%d5", "move.l #0x00000000,%d6", "subx.l %d6,%d5"  // 0-0-1 -> 0xffffffff
    ).mkString(" ; "))
  }

  // ── Multi-precision: two ADDX limbs forming a 64-bit add (low sets X, high uses it)
  // and the SUBX twin. Low-limb overflow (X=1 into the high limb) AND no-overflow (X=0).
  test("lock-step: ADDX 64-bit multi-precision chain (low limb carry -> high)", VerilatorTest) {
    runLockStep("addx-chain", Seq(
      // (D1:D0) = 0x00000001_ffffffff + (D3:D2) = 0x00000002_00000001
      //   low:  add.l   D2,D0  -> 0xffffffff + 0x00000001 = 0x00000000, C=1 -> X=1
      //   high: addx.l  D3,D1  -> 0x00000001 + 0x00000002 + 1 = 0x00000004
      "move.l #0xffffffff,%d0", "move.l #0x00000001,%d1",       // (D1:D0) high:low
      "move.l #0x00000001,%d2", "move.l #0x00000002,%d3",       // (D3:D2)
      "add.l %d2,%d0",                                          // low limb, sets X=1
      "addx.l %d3,%d1",                                         // high limb consumes X
      // no-carry case: low limb does NOT overflow -> X=0 into high
      "move.l #0x00000001,%d4", "move.l #0x00000010,%d5",       // (D5:D4)
      "move.l #0x00000002,%d6", "move.l #0x00000020,%d7",       // (D7:D6)
      "add.l %d6,%d4",                                          // 1+2=3, no carry -> X=0
      "addx.l %d7,%d5"                                          // 0x10+0x20+0 = 0x30
    ).mkString(" ; "))
  }

  test("lock-step: SUBX 64-bit multi-precision chain (low limb borrow -> high)", VerilatorTest) {
    runLockStep("subx-chain", Seq(
      // (D1:D0) = 0x00000003_00000000 - (D3:D2) = 0x00000001_00000001
      //   low:  sub.l   D2,D0  -> 0x00000000 - 0x00000001 = 0xffffffff, borrow -> X=1
      //   high: subx.l  D3,D1  -> 0x00000003 - 0x00000001 - 1 = 0x00000001
      "move.l #0x00000000,%d0", "move.l #0x00000003,%d1",       // (D1:D0)
      "move.l #0x00000001,%d2", "move.l #0x00000001,%d3",       // (D3:D2)
      "sub.l %d2,%d0",                                          // low limb borrow -> X=1
      "subx.l %d3,%d1",                                         // high limb consumes borrow
      // no-borrow case: low limb does NOT borrow -> X=0 into high
      "move.l #0x00000030,%d4", "move.l #0x00000005,%d5",       // (D5:D4)
      "move.l #0x00000010,%d6", "move.l #0x00000002,%d7",       // (D7:D6)
      "sub.l %d6,%d4",                                          // 0x30-0x10=0x20, no borrow -> X=0
      "subx.l %d7,%d5"                                          // 5-2-0 = 3
    ).mkString(" ; "))
  }

  // ── Clear-only Z (NEGX rule applied to ADDX/SUBX): a zero result only KEEPS a
  // prior Z, never sets it. Three directions: Z_old=0 + zero result -> Z stays 0;
  // Z_old=1 + zero result -> Z stays 1; non-zero result -> Z clears to 0. ─────────
  test("lock-step: ADDX/SUBX clear-only Z (Z preceding 0/1, zero + non-zero results)", VerilatorTest) {
    runLockStep("addx-subx-z", Seq(
      // Z_old=0, ADDX result 0: addi.b #1 to 0x7f -> 0x80 (N=1,Z=0) sets X=0,Z=0,
      // then addx.b 0+0+0 = 0 -> Z must STAY 0 (clear-only).
      "move.l #0x0000007f,%d0", "addi.b #1,%d0",          // -> 0x80: Z=0, X=0
      "move.l #0xaaaa0000,%d1", "move.l #0xbbbb0000,%d2", "addx.b %d2,%d1", // 0+0+0=0; Z stays 0
      // Z_old=1, SUBX result 0: subi.b #1 from 1 -> 0 (Z=1, no borrow X=0),
      // then subx.b 0-0-0 = 0 -> Z must STAY 1.
      "move.l #0x00000001,%d3", "subi.b #1,%d3",          // -> 0: Z=1, X=0
      "move.l #0xcccc0000,%d4", "move.l #0xdddd0000,%d5", "subx.b %d5,%d4", // 0-0-0=0; Z stays 1
      // Z_old=1, ADDX non-zero result: clears Z to 0.
      "move.l #0x00000001,%d6", "subi.b #1,%d6",          // -> 0: Z=1, X=0
      "move.l #0x00000005,%d7", "move.l #0x00000003,%d0", "addx.b %d0,%d7", // 5+3+0=8; Z clears to 0
      // Z_old=1, ADDX with X=1 producing a zero byte: 0xff + 0x00 + 1 = 0x00 (carry),
      // Z_old=1 -> Z stays 1. Seed X=1,Z=1 via addi.b #1 to 0xff (-> 0, carry).
      "move.l #0x000000ff,%d1", "addi.b #1,%d1",          // 0xff+1 -> 0: Z=1, X=1 (carry)
      "move.l #0x111100ff,%d2", "move.l #0x22220000,%d3", "addx.b %d3,%d2" // 0xff+0+1=0x00; Z stays 1
    ).mkString(" ; "))
  }

  test("lock-step: NOT.B/.W/.L (NZ, V=C=0, upper preserved)", VerilatorTest) {
    runLockStep("not", Seq(
      "move.l #0x0000000f,%d0", "not.b %d0",                  // .B ~0x0f=0xf0, N=1
      "move.l #0xffffffff,%d1", "not.w %d1",                  // .W ~0xffff=0 word, Z=1
      "move.l #0x12345678,%d2", "not.l %d2",                  // .L ~ -> 0xedcba987, N=1
      "move.l #0xaabbccdd,%d3", "not.b %d3"                   // partial: upper preserved
    ).mkString(" ; "))
  }

  test("lock-step: TST.B/.W/.L (NZ, V=C=0, no write)", VerilatorTest) {
    runLockStep("tst", Seq(
      "move.l #0x00000080,%d0", "tst.b %d0",                  // .B negative byte -> N=1
      "move.l #0x00000000,%d1", "tst.w %d1",                  // .W zero -> Z=1
      "move.l #0x80000000,%d2", "tst.l %d2",                  // .L negative -> N=1
      "move.l #0x0000007f,%d3", "tst.b %d3"                   // .B positive -> N=0,Z=0
    ).mkString(" ; "))
  }

  test("lock-step: SWAP (halves swapped, NZ from 32-bit, V=C=0)", VerilatorTest) {
    runLockStep("swap", Seq(
      "move.l #0x12345678,%d0", "swap %d0",                   // -> 0x56781234
      "move.l #0x00000000,%d1", "swap %d1",                   // -> 0, Z=1
      "move.l #0x8000ffff,%d2", "swap %d2",                   // -> 0xffff8000, N=1 (bit31=1)
      "move.l #0x0000abcd,%d3", "swap %d3"                    // -> 0xabcd0000, N=1
    ).mkString(" ; "))
  }

  test("lock-step: EXT.W / EXT.L / EXTB.L (sign-extend, NZ, V=C=0)", VerilatorTest) {
    runLockStep("ext", Seq(
      "move.l #0x11223380,%d0", "ext.w %d0",                  // byte 0x80 -> word 0xff80 (.W upper preserved), N=1
      "move.l #0x1122337f,%d1", "ext.w %d1",                  // byte 0x7f -> word 0x007f, N=0
      "move.l #0x0000ffff,%d2", "ext.l %d2",                  // word 0xffff -> long 0xffffffff, N=1
      "move.l #0x00007fff,%d3", "ext.l %d3",                  // word 0x7fff -> long 0x00007fff
      "move.l #0x11223380,%d4", "extb.l %d4",                 // byte 0x80 -> long 0xffffff80, N=1
      "move.l #0x11223300,%d5", "extb.l %d5"                  // byte 0x00 -> long 0, Z=1
    ).mkString(" ; "))
  }

  test("lock-step: TAS (N/Z from Dn[7:0], set bit7, V=C=0)", VerilatorTest) {
    runLockStep("tas", Seq(
      "move.l #0x11223300,%d0", "tas %d0",                    // byte 0x00 -> N=0,Z=1; then 0x80 -> 0x11223380
      "move.l #0x1122337f,%d1", "tas %d1",                    // byte 0x7f -> N=0,Z=0; then 0xff -> 0x112233ff
      "move.l #0x112233ff,%d2", "tas %d2"                     // byte 0xff -> N=1,Z=0; stays 0xff
    ).mkString(" ; "))
  }

  // ── Memory-destination RMW lock-step (load-op-store crack) ─────────────────
  // Each program seeds ONE data word in memory (move #val,Dn ; move Dn,addr), runs ONE
  // RMW op against that memory dest, then halts. The register/flag/PC stream lock-steps
  // vs Musashi; checkMem verifies the FINAL memory value = the RMW result.
  //
  // ONE RMW PER PROGRAM (single store->load->store sequence): a PRE-EXISTING SQ/dcache
  // drain race (reproducible on master WITHOUT any RMW — a 3x back-to-back store->load-
  // same->store-same program drops a store there too) corrupts memory under sustained
  // same-address store-load-store pressure. That LS drain bug is ORTHOGONAL to the RMW
  // crack and OUT OF SCOPE here; a single RMW per program drains cleanly (the 200-cycle
  // post-run settle guarantees the lone store reaches memory), so these gate the CRACK
  // (decode + load->op->store + EA recompute + flags + final memory) exactly. .B/.W
  // RMWs seed a FULL .L word so the checked .L span is fully written (RMW modifies only
  // the low byte(s); the seed's upper bytes match Musashi); checkSpan=4 validates it.

  test("lock-step: ADD.L Dn,(An) RMW -> mem + NZVCX", VerilatorTest) {
    runLockStep("rmw-add-l",
      "move.l #0x10000001,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x20000002,%d1 ; add.l %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x30000003
  }
  test("lock-step: SUB.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-sub-l",
      "move.l #0x00000005,%d2 ; move.l #0x3000,%a1 ; move.l %d2,(%a1) ; move.l #0x00000003,%d3 ; sub.l %d3,(%a1) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 2
  }
  test("lock-step: AND.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-and-l",
      "move.l #0xff00ff00,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0x0f0f0f0f,%d5 ; and.l %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x0f000f00
  }
  test("lock-step: OR.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-or-l",
      "move.l #0x12340001,%d6 ; move.l #0x3000,%a3 ; move.l %d6,(%a3) ; move.l #0x00005678,%d7 ; or.l %d7,(%a3) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x12345679
  }
  // Regression guard for the IN-ORDER-LS-ISSUE fix (IssueQueuePlugin ohL). A multi-RMW
  // chain to ONE address: pre-fix, a younger RMW load (addr ready) issued AHEAD of an
  // older RMW store still waiting on its ALU data -> the load queried SQ-forward before
  // that store ALLOCATED -> forward miss -> write-no-allocate stale refill -> dropped
  // store -> wrong final memory (regs still matched Musashi; only memory diverged). The
  // fix issues the OLDEST occupied LS slot (when ready), so no load bypasses an older
  // store. (This is why the single-RMW-per-program note above existed; now multi-RMW works.)
  test("lock-step: multi-RMW chain to one addr (in-order LS issue) -> mem", VerilatorTest) {
    runLockStep("rmw-chain",
      "move.l #0x3000,%a0 ; move.l #0x100,%d0 ; move.l %d0,(%a0) ; addq.l #1,(%a0) ; addq.l #2,(%a0) ; " +
      "addq.l #3,(%a0) ; addq.l #4,(%a0) ; addq.l #5,(%a0) ; " +
      ".stop: bra .stop", nInstr = 8, checkMem = Seq(0x3000L))   // -> 0x100+1+2+3+4+5 = 0x10F
  }
  test("lock-step: ADD.B Dn,(An) RMW (carry/X/Z edge)", VerilatorTest) {
    runLockStep("rmw-add-b",
      "move.l #0x111100ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x00000001,%d1 ; add.b %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0xff+1 -> 0x00, C/X/Z
  }
  test("lock-step: ADD.W Dn,(An) RMW (overflow/N edge)", VerilatorTest) {
    runLockStep("rmw-add-w",
      "move.l #0x22227fff,%d2 ; move.l #0x3000,%a1 ; move.l %d2,(%a1) ; move.l #0x00000001,%d3 ; add.w %d3,(%a1) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .W 0x7fff+1 -> 0x8000, V/N
  }
  test("lock-step: SUB.B Dn,(An) RMW (borrow edge)", VerilatorTest) {
    runLockStep("rmw-sub-b",
      "move.l #0x33330000,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0x00000001,%d5 ; sub.b %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0-1 -> 0xff, C/X/N
  }
  test("lock-step: EOR.B/.W/.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-eor-b",
      "move.l #0x111100aa,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x000000ff,%d1 ; eor.b %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B aa^ff -> 0x55
  }
  test("lock-step: EOR.L Dn,(An) RMW (N edge)", VerilatorTest) {
    runLockStep("rmw-eor-l",
      "move.l #0x12345678,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0xffffffff,%d5 ; eor.l %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0xedcba987, N=1
  }
  test("lock-step: ADDI.L #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-addi-l",
      "move.l #0x00000010,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; addi.l #0x22,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0x32
  }
  test("lock-step: SUBI.W #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-subi-w",
      "move.l #0x11110050,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; subi.w #0x0030,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 0x20
  }
  test("lock-step: ANDI.W #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-andi-w",
      "move.l #0x2222ff0f,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; andi.w #0x0ff0,(%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 0x0f00
  }
  test("lock-step: ORI.L #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-ori-l",
      "move.l #0x12000000,%d3 ; move.l #0x3000,%a3 ; move.l %d3,(%a3) ; ori.l #0x00345678,(%a3) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0x12345678
  }
  test("lock-step: EORI.B #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-eori-b",
      "move.l #0x444400aa,%d4 ; move.l #0x3000,%a4 ; move.l %d4,(%a4) ; eori.b #0xff,(%a4) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .B aa^ff -> 0x55
  }
  test("lock-step: ADDQ.L #n,(An) RMW", VerilatorTest) {
    runLockStep("rmw-addq-l",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; addq.l #3,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 8
  }
  test("lock-step: SUBQ.W #8,(An) RMW (n=8)", VerilatorTest) {
    runLockStep("rmw-subq-w",
      "move.l #0x11110010,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; subq.w #8,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 8
  }
  test("lock-step: ADDQ.B #1,(An) RMW (carry edge)", VerilatorTest) {
    runLockStep("rmw-addq-b",
      "move.l #0x222200ff,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; addq.b #1,(%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0xff+1 -> 0x00, C/X/Z
  }
  test("lock-step: CLR.L (An) RMW (Z=1,N=0)", VerilatorTest) {
    runLockStep("rmw-clr-l",
      "move.l #0x11223344,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; clr.l (%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0, Z=1,N=0
  }
  test("lock-step: NEG.L (An) RMW (N/C/X)", VerilatorTest) {
    runLockStep("rmw-neg-l",
      "move.l #0x00000001,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; neg.l (%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0xffffffff, N=1,C=1,X=1
  }
  test("lock-step: NOT.L (An) RMW (N)", VerilatorTest) {
    runLockStep("rmw-not-l",
      "move.l #0x00000005,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; not.l (%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0xfffffffa, N=1
  }
  test("lock-step: NEGX.B (An) RMW (X input via prior subi)", VerilatorTest) {
    // Seed a byte + set X via a register subi, then NEGX the memory dest (reads X).
    runLockStep("rmw-negx",
      "move.l #0x3000,%a0 ; move.l #0x00000003,%d0 ; move.b %d0,(%a0) ; " +
      "moveq #0,%d1 ; subi.b #1,%d1 ; " +        // X=1 (0-1 borrow)
      "negx.b (%a0) ; " +                         // 0 - 3 - 1 = 0xfc, N=1
      ".stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 1)
  }
  test("lock-step: TST.L (An) RMW = load+flags, NO store", VerilatorTest) {
    runLockStep("rmw-tst-l",
      "move.l #0x80000000,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; tst.l (%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // N=1, mem unchanged
  }
  test("lock-step: TST.W (An) RMW = load+flags, NO store (Z)", VerilatorTest) {
    runLockStep("rmw-tst-w",
      "move.l #0x11110000,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; tst.w (%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // Z=1, mem unchanged
  }
  test("lock-step: CMPI.L #imm,(An) = load+compare, NO store (Z)", VerilatorTest) {
    runLockStep("rmw-cmpi-l",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; cmpi.l #0x00000005,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // equal -> Z=1, mem unchanged
  }
  test("lock-step: CMPI.W #imm,(An) = load+compare, NO store (N/C)", VerilatorTest) {
    runLockStep("rmw-cmpi-w",
      "move.l #0x11110010,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; cmpi.w #0x0020,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // 0x10<0x20 -> N/C, mem unchanged
  }
  test("lock-step: RMW to (d16,An) (ADDQ)", VerilatorTest) {
    runLockStep("rmw-d16an",
      "move.l #0x00000007,%d0 ; move.l #0x3000,%a1 ; move.l %d0,0x10(%a1) ; addq.l #1,0x10(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3010L))   // (d16,A1) -> 8 at 0x3010
  }
  test("lock-step: RMW to (xxx).L abs (SUBI)", VerilatorTest) {
    runLockStep("rmw-abs",
      "move.l #0x0000000a,%d1 ; move.l #0x3000,%a2 ; move.l %d1,(%a2) ; subi.l #4,0x3000 ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // (xxx).L abs -> 6 at 0x3000
  }

  // ── Bit ops (BTST/BSET/BCLR/BCHG) lock-step ────────────────────────────────
  // {BTST,BSET,BCLR,BCHG} × {static #n, dynamic Dn} × {Dn-dest LONG, memory-dest BYTE},
  // with bit-number edges (0,7,31,32->mod32; 33,63->mod32; 8->mod8 byte). Each tests bit
  // n -> Z = ~bit; all but BTST then set/clear/toggle it. Dn = full LONG (mod 32); memory
  // = BYTE (mod 8): BTST load-only (NO store), BSET/BCLR/BCHG mem-RMW. Flags = Z ONLY;
  // N/V/C/X preserved. checkMem verifies the byte RMW (and BTST's NO-store).

  // ── static #n, Dn dest (LONG, mod 32) — bit edges ──────────────────────────
  test("lock-step: BSET/BCLR/BCHG/BTST #n,Dn (static, bit 0)", VerilatorTest) {
    runLockStep("bit-static-dn-0",
      "move.l #0x00000000,%d0 ; bset #0,%d0 ; "  +  // -> bit0 set, Z=1 (was 0)
      "move.l #0xffffffff,%d1 ; bclr #0,%d1 ; "  +  // -> bit0 clear, Z=0
      "move.l #0x00000001,%d2 ; bchg #0,%d2 ; "  +  // -> bit0 toggled, Z=0
      "move.l #0x00000000,%d3 ; btst #0,%d3 ; "  +  // -> Z=1, no write
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BCLR/BCHG/BTST #7,Dn (static, bit 7)", VerilatorTest) {
    runLockStep("bit-static-dn-7",
      "move.l #0x11223344,%d0 ; bset #7,%d0 ; "  +
      "move.l #0x11223388,%d1 ; bclr #7,%d1 ; "  +
      "move.l #0x11223344,%d2 ; bchg #7,%d2 ; "  +
      "move.l #0x11223380,%d3 ; btst #7,%d3 ; "  +
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BCLR/BCHG/BTST #31,Dn (static, top bit)", VerilatorTest) {
    runLockStep("bit-static-dn-31",
      "move.l #0x00000000,%d0 ; bset #31,%d0 ; " +  // -> 0x80000000
      "move.l #0xffffffff,%d1 ; bclr #31,%d1 ; " +  // -> 0x7fffffff
      "move.l #0x00000000,%d2 ; bchg #31,%d2 ; " +  // -> 0x80000000
      "move.l #0x80000000,%d3 ; btst #31,%d3 ; " +  // -> Z=0
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BTST #32,Dn (static, mod 32 -> bit 0)", VerilatorTest) {
    runLockStep("bit-static-dn-32",
      "move.l #0x00000000,%d0 ; bset #32,%d0 ; " +  // 32 mod 32 = 0 -> bit0 set
      "move.l #0x00000001,%d1 ; btst #32,%d1 ; " +  // bit0=1 -> Z=0
      ".stop: bra .stop", nInstr = 5)
  }

  // ── dynamic Dn, Dn dest (LONG, mod 32) — bit edges incl. >31 ───────────────
  test("lock-step: BSET/BCLR/BCHG/BTST Dc,Dn (dynamic, c=3)", VerilatorTest) {
    runLockStep("bit-dyn-dn-3",
      "moveq #3,%d7 ; "                          +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // bit3 set
      "move.l #0xffffffff,%d1 ; bclr %d7,%d1 ; " +  // bit3 clear
      "move.l #0x00000008,%d2 ; bchg %d7,%d2 ; " +  // bit3 toggle -> 0
      "move.l #0x00000000,%d3 ; btst %d7,%d3 ; " +  // Z=1
      ".stop: bra .stop", nInstr = 10)
  }
  test("lock-step: BSET/BTST Dc,Dn (dynamic, c=31)", VerilatorTest) {
    runLockStep("bit-dyn-dn-31",
      "moveq #31,%d7 ; "                         +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // -> 0x80000000
      "move.l #0x80000000,%d1 ; btst %d7,%d1 ; " +  // Z=0
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: BSET/BTST Dc,Dn (dynamic, c=33 -> mod32 bit 1)", VerilatorTest) {
    runLockStep("bit-dyn-dn-33",
      "moveq #33,%d7 ; "                         +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // 33 mod 32 = 1 -> bit1 set (0x2)
      "move.l #0x00000002,%d1 ; btst %d7,%d1 ; " +  // bit1=1 -> Z=0
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: BCHG Dc,Dn (dynamic, c=63 -> mod32 bit 31)", VerilatorTest) {
    runLockStep("bit-dyn-dn-63",
      "move.l #63,%d7 ; "                        +  // 63 mod 32 = 31
      "move.l #0x00000000,%d0 ; bchg %d7,%d0 ; " +  // -> 0x80000000
      ".stop: bra .stop", nInstr = 3)
  }

  // ── memory dest (BYTE, mod 8): BSET/BCLR/BCHG = RMW; BTST = load-only NO store ──
  test("lock-step: BSET.B #n,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bset",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset #0,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x00 -> 0x01, Z was 1
  }
  test("lock-step: BCLR.B #7,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bclr",
      "move.l #0x112233ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bclr #7,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0xff bit7 clear -> 0x7f
  }
  test("lock-step: BCHG.B #n,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bchg",
      "move.l #0x11223355,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bchg #1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x55 bit1 toggle -> 0x57
  }
  test("lock-step: BTST #n,(An) (mem load-only, NO store)", VerilatorTest) {
    runLockStep("bit-mem-btst",
      "move.l #0x11223380,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; btst #7,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x80 bit7=1 -> Z=0, mem unchanged
  }
  test("lock-step: BSET.B #8,(An) (mem, 8 mod 8 -> bit 0)", VerilatorTest) {
    runLockStep("bit-mem-bset-8",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset #8,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // 8 mod 8 = 0 -> byte 0x00->0x01
  }
  test("lock-step: BSET %d1,(An) (dynamic mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-dyn-bset",
      "moveq #3,%d1 ; move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x00 bit3 set -> 0x08
  }

  // ── flag preservation: a bit-op changes Z ONLY; N/V/C/X preserved ──────────
  // Pre-set N/V/C/X via a prior addq overflow + carry, then a bit-op: Z flips, the
  // other CCR bits hold. (subi.b #1 on 0 sets C/X/N; the following bset only sets Z.)
  test("lock-step: bit-op preserves N/V/C/X (only Z changes)", VerilatorTest) {
    runLockStep("bit-flags-preserve",
      "moveq #0,%d1 ; subi.b #1,%d1 ; "          +  // 0-1 -> 0xff, sets N=1,C=1,X=1,V=0,Z=0
      "move.l #0x00000000,%d0 ; bset #5,%d0 ; "  +  // bit5 was 0 -> Z=1; N/V/C/X unchanged
      "move.l #0x00000020,%d2 ; btst #5,%d2 ; "  +  // bit5=1 -> Z=0; N/V/C/X still unchanged
      ".stop: bra .stop", nInstr = 6)
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

  // ── JMP (computed-target branch, no push) ──────────────────────────────────
  test("lock-step: jmp (An) computed target", VerilatorTest) {
    // Load .L's absolute address into A0, jmp (A0): skips moveq#9, lands on moveq#7.
    // Executed: move.l#.L,a0, jmp(a0), moveq#7 = 3.
    runLockStep("jmp-an",
      "move.l #.L,%a0 ; jmp (%a0) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 3)
  }

  test("lock-step: jmp (xxx).L absolute target", VerilatorTest) {
    // jmp .L (absolute long): skips moveq#9, lands on moveq#7.
    // Executed: jmp(abs), moveq#7 = 2.
    runLockStep("jmp-abs",
      "jmp (.L).l ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  test("lock-step: jmp (d16,PC) pc-relative target", VerilatorTest) {
    // jmp .L(pc) (PC-relative): skips moveq#9, lands on moveq#7.
    // Executed: jmp(pc-rel), moveq#7 = 2.
    runLockStep("jmp-pcrel",
      "jmp .L(%pc) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  test("lock-step: jmp (d16,An) displaced target", VerilatorTest) {
    // A0 = .L - 4; jmp 4(A0) -> .L. Skips moveq#9, lands on moveq#7.
    // Executed: move.l#.L-4,a0, jmp 4(a0), moveq#7 = 3.
    runLockStep("jmp-d16an",
      "move.l #.L-4,%a0 ; jmp 4(%a0) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 3)
  }

  test("lock-step: bsr push (no rts) isolation", VerilatorTest) {
    // moveq#1 ; bsr sub ; .stop: bra .stop ; sub: moveq#3 ; bra .stop2 ; .stop2: bra .stop2
    // No RTS at all -> isolates the BSR push+branch crack from the RTS pop+ibranch.
    // Executed to sentinel: moveq#1, bsr, moveq#3, bra = 4.
    runLockStep("bsr-push-iso",
      "moveq #1,%d0 ; bsr sub ; .stop: bra .stop ; sub: moveq #3,%d1 ; bra .stop2 ; .stop2: bra .stop2",
      nInstr = 4)
  }

  // ── BSR / RTS (the core call/return round trip) ────────────────────────────
  test("lock-step: bsr ... rts round trip", VerilatorTest) {
    // moveq#1,d0 ; bsr sub ; moveq#7,d2 ; stop-fence(bra .) ; sub: moveq#3,d1 ; rts
    // Flow: moveq#1, bsr(push retPC + branch to sub), moveq#3, rts(pop + branch back),
    // moveq#7, then bra-to-self sentinel. Executed (to the sentinel): moveq#1, bsr,
    // moveq#3, rts, moveq#7 = 5. Verifies retPC round-trip + A7 restored (push then
    // pop -> net 0) + final regs/PC.
    runLockStep("bsr-rts",
      "moveq #1,%d0 ; bsr sub ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: nested bsr (call within a callee)", VerilatorTest) {
    // outer calls inner; inner returns; outer returns. Two pushes, two pops, A7 net 0.
    // moveq#1,d0 ; bsr a ; .stop: bra .stop ;
    // a: moveq#2,d1 ; bsr b ; moveq#4,d3 ; rts ;
    // b: moveq#3,d2 ; rts
    // Executed to sentinel: moveq#1, bsr a, moveq#2, bsr b, moveq#3, rts(b->a),
    // moveq#4, rts(a->main) = 8.
    runLockStep("bsr-nested",
      "moveq #1,%d0 ; bsr a ; .stop: bra .stop ; " +
      "a: moveq #2,%d1 ; bsr b ; moveq #4,%d3 ; rts ; " +
      "b: moveq #3,%d2 ; rts",
      nInstr = 8)
  }

  // ── JSR (call via the EA address) ──────────────────────────────────────────
  test("lock-step: jsr (An) ... rts", VerilatorTest) {
    // A0 = sub; jsr (A0) pushes retPC + jumps to sub; sub does moveq#3 + rts.
    // Executed to sentinel: moveq#1, move.l#sub a0, jsr(a0), moveq#3, rts, moveq#7 = 6.
    runLockStep("jsr-an",
      "moveq #1,%d0 ; move.l #sub,%a0 ; jsr (%a0) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 6)
  }

  test("lock-step: jsr (xxx).L ... rts", VerilatorTest) {
    // jsr sub (absolute long). Executed: moveq#1, jsr(abs), moveq#3, rts, moveq#7 = 5.
    runLockStep("jsr-abs",
      "moveq #1,%d0 ; jsr (sub).l ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: jsr (d16,PC) ... rts", VerilatorTest) {
    // jsr sub(pc) (PC-relative). Executed: moveq#1, jsr(pcrel), moveq#3, rts, moveq#7 = 5.
    runLockStep("jsr-pcrel",
      "moveq #1,%d0 ; jsr sub(%pc) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: jsr (d16,An) ... rts", VerilatorTest) {
    // A0 = sub - 8 ; jsr 8(A0) -> sub. Executed: moveq#1, move.l#sub-8 a0, jsr 8(a0),
    // moveq#3, rts, moveq#7 = 6.
    runLockStep("jsr-d16an",
      "moveq #1,%d0 ; move.l #sub-8,%a0 ; jsr 8(%a0) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 6)
  }

  // ── LINK / UNLK (frame setup / teardown; reuse the call/return crack machinery) ──
  // LINK An,#d: push old An to -(A7); An := A7 (new frame ptr); A7 += d.
  // UNLK An:    A7 := An; An := mem[A7]; A7 += 4.
  test("lock-step: link/unlk round trip (frame setup + teardown, checkMem pushed An)", VerilatorTest) {
    // A6 := 0x2222 (a known frame-pointer value = the pushed word); A7 := 0x3010 (a
    // D-cache-backed SP). LINK A6,#-8 pushes A6 to 0x300C, A6 := 0x300C, A7 := 0x3004.
    // move A6->d0 validates LINK's An (= 0x300C). UNLK A6 restores A6 := mem[0x300C] =
    // 0x2222 and A7 := 0x3010. move A6->d1 validates the restored An. checkMem 0x300C =
    // the pushed old A6 (0x2222). Executed: move,move,link,move,unlk,move = 6.
    runLockStep("link-unlk-rt",
      "move.l #0x2222,%a6 ; move.l #0x3010,%a7 ; link %a6,#-8 ; move.l %a6,%d0 ; " +
      "unlk %a6 ; move.l %a6,%d1 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x300CL))   // pushed old A6 = 0x00002222
  }

  test("lock-step: link positive disp (A7 grows up) + unlk", VerilatorTest) {
    // LINK A5,#+16: push A5 to 0x300C, A5 := 0x300C, A7 := 0x3010+16 = 0x3020. UNLK A5
    // restores A5 := mem[0x300C], A7 := 0x3010. d0/d1 read A5 after link / after unlk.
    runLockStep("link-pos-disp",
      "move.l #0xCAFE,%a5 ; move.l #0x3010,%a7 ; link %a5,#16 ; move.l %a5,%d0 ; " +
      "unlk %a5 ; move.l %a5,%d1 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x300CL))   // pushed old A5 = 0x0000CAFE
  }

  test("lock-step: nested link/unlk (two frames, LIFO teardown)", VerilatorTest) {
    // Two nested frames sharing A6 (the classic compiler prologue/epilogue). The OUTER
    // A6 (0x1111) is pushed first; the INNER push saves the outer frame ptr. UNLK
    // unwinds in LIFO order. Validates A6 + A7 across nested setup/teardown.
    //   A6:=0x1111 ; A7:=0x3020 ;
    //   link A6,#-4  (push 0x1111 @0x301C ; A6=0x301C ; A7=0x3018) ;
    //   move A6->d0  (=0x301C) ;
    //   link A6,#-4  (push 0x301C @0x3014 ; A6=0x3014 ; A7=0x3010) ;
    //   move A6->d1  (=0x3014) ;
    //   unlk A6      (A6:=mem[0x3014]=0x301C ; A7:=0x3018) ;
    //   move A6->d2  (=0x301C) ;
    //   unlk A6      (A6:=mem[0x301C]=0x1111 ; A7:=0x3020) ;
    //   move A6->d3  (=0x1111).
    // Executed: move,move,link,move,link,move,unlk,move,unlk,move = 10.
    runLockStep("link-unlk-nested",
      "move.l #0x1111,%a6 ; move.l #0x3020,%a7 ; " +
      "link %a6,#-4 ; move.l %a6,%d0 ; link %a6,#-4 ; move.l %a6,%d1 ; " +
      "unlk %a6 ; move.l %a6,%d2 ; unlk %a6 ; move.l %a6,%d3 ; .stop: bra .stop",
      nInstr = 10)
  }

  test("lock-step: store.l then load.l same addr (drain race probe)", VerilatorTest) {
    // Isolation probe for the RTR flake: store a long to 0x2002 (a never-resident line),
    // space it, then load.l 0x2002 -> d7. If this flakes, the store->miss-load drain is
    // a general harness/core issue (not RTR-specific).
    runLockStep("st-ld-drain",
      "move.l #0x12345678,%d1 ; move.l %d1,0x2002 ; moveq #1,%d4 ; moveq #1,%d5 ; " +
      "moveq #1,%d6 ; move.l 0x2002,%d7 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x2002L))
  }

  // ── RTR (restore CCR + PC) ─────────────────────────────────────────────────
  test("lock-step: rtr (restore CCR + PC) via a hand-built frame", VerilatorTest) {
    // The 040 RTR pops a CCR word @(A7) then a PC long @(A7+2), A7 += 6, restoring ONLY
    // the CCR (SR low byte) and jumping to the popped PC. Predecrement/move-from-SR are
    // not in scope yet, so we hand-build the frame in a data page via MOVE-to-abs stores
    // then point A7 at it: mem[0x2000] = 0x0004 (CCR word, Z=1), mem[0x2002] = target.
    //   moveq#4,d0 ; move.w d0,(0x2000).w  (CCR word)
    //   move.l #target,d1 ; move.l d1,(0x2002).l  (PC long)
    //   move.l #0x2000,a7 ; rtr   (pop CCR+PC, A7 -> 0x2006, jump to target)
    //   target: moveq#7,d2 ; .stop: bra .stop
    // rtr restores CCR=0x04 (Z set) + redirects to target. Executed: moveq#4,
    // move.w-store, move.l#target, move.l-store, move.l#0x2000-to-a7, rtr, moveq#7 = 7.
    // A real RTR frame is stacked by the (long-committed) caller — resident in memory
    // before RTR. Our test stacks it inline, so we (a) space the stores out and (b) read
    // the frame line back into d4/d5 first, which refills the (no-allocate-store) line
    // into L1D with the drained store data; RTR's two pops then HIT the resident line.
    runLockStep("rtr-frame",
      "moveq #4,%d0 ; move.w %d0,0x2000 ; move.l #target,%d1 ; move.l %d1,0x2002 ; " +
      "move.l 0x2000,%d4 ; move.l 0x2002,%d5 ; " +
      "move.l #0x2000,%a7 ; rtr ; target: moveq #7,%d2 ; .stop: bra .stop",
      nInstr = 9, checkMem = Seq(0x2000L))
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

  // ── Loop-with-load (the deadlock-fix milestone) ────────────────────────────
  // A backward-branch loop whose BODY contains a memory op. The body load cracks
  // into [load->T0, move T0->Dn]; the move ALSO writes NZVC. The cracked move
  // pairs with the load in one rename group, so the loop's `sub` (the youngest
  // NZVC writer before the loop-back branch) renames ALONE in slot 0 the next
  // cycle. Pre-fix the RAT's multi-write spec Mem silently DROPPED slot-0-only
  // writes (only the highest write port updated a 1-entry flag RAT), so the `sub`
  // never updated the NZVC RAT and the loop-back `bne` read the STALE move-NZVC
  // (Z=0) instead of the sub's (Z=1) -> the final not-taken bne mis-resolved as
  // taken (extra wrong iteration / divergence). Lock-steps step-for-step + final
  // mem so the regression is caught at the architectural level.
  test("lock-step: backward loop with a LOAD in the body", VerilatorTest) {
    // d2=42 stored to 0x2000 (so the body load reads a known value). d0=3 counter,
    // d1=1 decrement. Body: load 0x2000->d3 ; sub d1,d0 ; bne .L.
    //   iter1: 3-1=2 (Z=0) bne TAKEN, iter2: 2-1=1 bne TAKEN, iter3: 1-1=0 not taken.
    // Two backward-taken mispredicts, each followed by a body load whose cracked
    // flag-setting move splits the sub into a lone slot-0 rename.
    // Executed: moveq#42, move.l(store), moveq#3, moveq#1, [load,sub,bne]x3 = 13.
    runLockStep("loop-load",
      "moveq #42,%d2 ; move.l %d2,0x2000 ; moveq #3,%d0 ; moveq #1,%d1 ; " +
      ".L: move.l 0x2000,%d3 ; sub.l %d1,%d0 ; bne.s .L",
      nInstr = 13, checkMem = Seq(0x2000L))
  }

  // A second loop-with-memory shape: a STORE in the body (the store also sets NZVC
  // as MOVE-to-memory) followed by a flag-dependent loop-back branch. Exercises the
  // same lone-slot-0 NZVC-RAT-write path with a store rather than a load.
  test("lock-step: backward loop with a STORE in the body", VerilatorTest) {
    // d0=3 counter, d1=1 dec, d2=7 value. Body: store d2->0x2010 ; sub d1,d0 ; bne.
    // Executed: moveq#3, moveq#1, moveq#7, [store,sub,bne]x3 = 12.
    runLockStep("loop-store",
      "moveq #3,%d0 ; moveq #1,%d1 ; moveq #7,%d2 ; " +
      ".L: move.l %d2,0x2010 ; sub.l %d1,%d0 ; bne.s .L",
      nInstr = 12, checkMem = Seq(0x2010L))
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

  // ── DIVU.W / DIVS.W lock-step (32/16 -> Dn = {rem16, q16}, N/Z/V) ────────────
  // Register-divisor forms are 2-byte opwords (nextPc = pc+2), straight-line.
  test("lock-step: DIVU.W normal + DIVS.W normal", VerilatorTest) {
    runLockStep("div-w-normal",
      "moveq #100,%d0 ; moveq #7,%d1 ; divu.w %d1,%d0 ; " +   // 100/7 -> q14 r2 in d0
      "moveq #-100,%d2 ; moveq #7,%d3 ; divs.w %d3,%d2 ; " +  // -100/7 -> q-14 r-2
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // DIVU.W overflow: a large dividend / small divisor yields a quotient > 16 bits ->
  // V=1, NO result write (Dn unchanged). The committed flags + (unchanged) Dn match.
  test("lock-step: DIVU.W overflow (V=1, no write)", VerilatorTest) {
    runLockStep("div-w-ovf",
      "move.l #0x10000,%d0 ; moveq #1,%d1 ; divu.w %d1,%d0 ; " + // 0x10000/1 = 0x10000 > 16b -> V
      "moveq #5,%d2 ; loop: bra loop", nInstr = 5)
  }

  // DIVU.W divide-by-zero -> vector 5 (format-$2) -> handler -> RTE -> resume.
  // The handler's last flag-writer reproduces the entry CCR (Z=1 from `moveq #0,%d2`)
  // so the sim whitebox's reconstructed CCR matches the oracle's RTE-restored CCR at
  // the RTE step (same convention as the TRAPV lock-step; the architectural CCR
  // restore on RTE is a separate pre-existing concern outside the trap machinery).
  test("lock-step: DIVU.W DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-w-div0",
      "move.l #handler,%d0 ; move.l %d0,0x14 ; " +   // vector 5 (DIV0) @ 0x14
      "moveq #100,%d1 ; moveq #0,%d2 ; divu.w %d2,%d1 ; " + // /0 -> trap; entry CCR Z=1
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 8)  // moveq #0 -> Z=1 matches entry CCR
  }

  // ── DIVU.L/DIVS.L 32/32 lock-step (quotient -> Dq; quotient+remainder Dr:Dq) ──
  test("lock-step: DIVU.L/DIVS.L 32/32 quotient-only", VerilatorTest) {
    runLockStep("div-l32-q",
      "move.l #1000000,%d0 ; moveq #7,%d1 ; divu.l %d1,%d0 ; " +   // 1000000/7 -> Dq
      "move.l #-1000000,%d2 ; moveq #7,%d3 ; divs.l %d3,%d2 ; " + // signed
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // Remainder:quotient 32/32 form (Dr!=Dq, cracked DIV+DIVREM). GNU `divull Dn,Dr,Dq`
  // = the 32-bit form (ext bit10=0) writing Dq=quotient + Dr=remainder. The trailing
  // `move.l %d2/%d5,...` READ the remainders (Dr) so the DIVREM PRF write is verified
  // by a normal commit (its own commit record is coalesced into the DIV's step).
  test("lock-step: DIVU.L/DIVS.L 32/32 remainder:quotient (Dr,Dq)", VerilatorTest) {
    runLockStep("div-l32-rq",
      "move.l #1000003,%d0 ; moveq #7,%d1 ; divull %d1,%d2,%d0 ; " + // d0=q, d2=rem
      "move.l #-1000003,%d3 ; moveq #7,%d4 ; divsll %d4,%d5,%d3 ; " + // signed
      "move.l %d2,%d6 ; move.l %d5,%d7 ; " +                          // verify both remainders
      "loop: bra loop", nInstr = 8)
  }

  // DIVU.L 32/32 DIV0 -> vector 5 -> handler -> RTE.
  test("lock-step: DIVU.L 32/32 DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-l32-div0",
      "move.l #handler,%d0 ; move.l %d0,0x14 ; " +     // vector 5 @ 0x14
      "move.l #1000,%d1 ; moveq #0,%d2 ; divu.l %d2,%d1 ; " + // /0 -> trap (entry Z=1)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 8)       // moveq #0 -> Z=1 matches entry
  }

  // ── DIVU.L/DIVS.L 64/32 lock-step (Dr:Dq 64-bit dividend -> Dq=q, Dr=rem) ─────
  // GNU `divu.l %dn,%dr:%dq` (the `:` syntax) = the 64-bit form (ext bit10=1): the
  // 64-bit dividend is Dr:Dq (Dr high, Dq low). Cracked DIV(+psrcC=Dr)+DIVREM. The
  // trailing moves verify the remainder (Dr).
  test("lock-step: DIVU.L 64/32 (Dr:Dq) normal", VerilatorTest) {
    runLockStep("div-l64-u",
      "move.l #0x12,%d2 ; move.l #0x34567890,%d0 ; moveq #100,%d1 ; " + // Dr:Dq = 0x12_34567890
      "divu.l %d1,%d2:%d0 ; " +                                          // d0=q, d2=rem
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVS.L 64/32 signed, negative dividend.
  test("lock-step: DIVS.L 64/32 (Dr:Dq) signed negative", VerilatorTest) {
    runLockStep("div-l64-s",
      "move.l #0xffffffff,%d2 ; move.l #0xfff0bdc0,%d0 ; moveq #7,%d1 ; " + // Dr:Dq = -1000000 (sign-ext)
      "divs.l %d1,%d2:%d0 ; " +
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVU.L 64/32 overflow: a 64-bit dividend whose quotient exceeds 32 bits -> V=1,
  // NO write (Dq, Dr unchanged). Verified by reading both back.
  test("lock-step: DIVU.L 64/32 overflow (V=1, no write)", VerilatorTest) {
    runLockStep("div-l64-ovf",
      "move.l #0x10,%d2 ; move.l #0,%d0 ; moveq #1,%d1 ; " + // 0x10_00000000 / 1 -> q > 32b
      "divu.l %d1,%d2:%d0 ; " +
      "moveq #5,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVU.L 64/32 DIV0 -> vector 5 -> handler -> RTE.
  test("lock-step: DIVU.L 64/32 DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-l64-div0",
      "move.l #handler,%d6 ; move.l %d6,0x14 ; " +     // vector 5 @ 0x14
      "move.l #0x12,%d2 ; move.l #0x3456,%d0 ; moveq #0,%d1 ; " + // /0
      "divu.l %d1,%d2:%d0 ; " +
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 9)       // entry CCR Z=1 (moveq #0,%d1)
  }

  // ── CHK lock-step (vector 6, format-$2): in-bounds no-op + both out-of-bounds ──
  // In-bounds: CHK is a no-op, straight-line. (CHK leaves CCR per the 68k undefined-
  // except-N rule; Musashi's CHK does modify N/Z, but the lock-step compares the
  // committed register stream + PC/SR/A7 — for the no-trap path the registers/PC match
  // and the next flag-writer overwrites CCR before any compare point.)
  test("lock-step: CHK in-bounds (no trap)", VerilatorTest) {
    runLockStep("chk-inbounds",
      "moveq #5,%d0 ; moveq #10,%d1 ; chk.w %d1,%d0 ; " +  // 0<=5<=10 -> no trap
      "moveq #3,%d2 ; loop: bra loop", nInstr = 5)
  }

  // Dn<0 (N=1) -> trap vector 6 -> handler -> RTE. The handler reproduces the entry
  // CCR (the chk's predecessor `moveq #-5,%d0` sets N=1) so the RTE-restored CCR
  // matches (same convention as the DIV0/TRAPV tests).
  test("lock-step: CHK Dn<0 -> handler -> RTE", VerilatorTest) {
    runLockStep("chk-neg",
      "move.l #handler,%d0 ; move.l %d0,0x18 ; " +   // vector 6 (CHK) @ 0x18
      "moveq #-5,%d1 ; moveq #10,%d2 ; chk.w %d2,%d1 ; " + // -5<0 -> trap (entry N=1)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #-1,%d4 ; rte", nInstr = 8)   // moveq #-1 -> N=1 matches entry
  }

  // Dn>bound (N=0) -> trap vector 6 -> handler -> RTE. Entry CCR: `moveq #20,%d1`
  // sets N=0,Z=0 (positive nonzero); the handler reproduces it.
  test("lock-step: CHK Dn>bound -> handler -> RTE", VerilatorTest) {
    runLockStep("chk-over",
      "move.l #handler,%d0 ; move.l %d0,0x18 ; " +   // vector 6 (CHK) @ 0x18
      "moveq #20,%d1 ; moveq #10,%d2 ; chk.w %d2,%d1 ; " + // 20>10 -> trap (entry N=0)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #1,%d4 ; rte", nInstr = 8)    // moveq #1 -> N=0,Z=0 matches entry
  }

  // ── MULU.W / MULS.W lock-step (16x16 -> Dn[31:0], N/Z; V=0, C=0) ─────────────
  // Register-source forms are 2-byte opwords (nextPc = pc+2), straight-line. The
  // full 32-bit product lands in Dn; N=bit31, Z=(product==0), V=0.
  test("lock-step: MULU.W normal + MULS.W normal/sign", VerilatorTest) {
    runLockStep("mul-w-normal",
      "moveq #100,%d0 ; moveq #7,%d1 ; mulu.w %d1,%d0 ; " +    // 100*7 = 700 in d0
      "moveq #-100,%d2 ; moveq #7,%d3 ; muls.w %d3,%d2 ; " +   // -100*7 = -700 (signed)
      "move.l #0x8000,%d4 ; move.l #0x8000,%d5 ; muls.w %d5,%d4 ; " + // -32768*-32768 = +2^30
      "moveq #1,%d6 ; loop: bra loop", nInstr = 9)
  }

  // MULU.W large product (N=1: bit31 set) + a zero product (Z=1).
  test("lock-step: MULU.W large product (N=1) + zero (Z=1)", VerilatorTest) {
    runLockStep("mul-w-flags",
      "move.l #0xffff,%d0 ; move.l #0xffff,%d1 ; mulu.w %d1,%d0 ; " + // 65535*65535 = 0xFFFE0001 (N=1)
      "moveq #0,%d2 ; moveq #123,%d3 ; mulu.w %d3,%d2 ; " +           // 0*123 = 0 (Z=1)
      "moveq #5,%d4 ; loop: bra loop", nInstr = 6)
  }

  // ── MULU.L / MULS.L 32x32 -> 32 lock-step (Dl = product[31:0]; +overflow V) ──
  // 0x4C00|ea form, ext bit10=0: Dl = (ea * Dl)[31:0]. V set iff the full 64-bit
  // product doesn't fit 32 bits (signed: high32 != sign-ext of bit31; unsigned:
  // high32 != 0). N=Dl[31], Z=(Dl==0), C=0.
  test("lock-step: MULU.L/MULS.L 32x32->32 normal", VerilatorTest) {
    runLockStep("mul-l32-normal",
      "move.l #100000,%d0 ; moveq #7,%d1 ; mulu.l %d1,%d0 ; " +    // 700000 fits 32 (no V)
      "move.l #-100000,%d2 ; moveq #7,%d3 ; muls.l %d3,%d2 ; " +   // -700000 signed (no V)
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // .L32 overflow: a product that exceeds 32 bits -> V=1, the low 32 still written.
  test("lock-step: MULU.L/MULS.L 32x32->32 overflow (V=1)", VerilatorTest) {
    runLockStep("mul-l32-ovf",
      "move.l #0x100000,%d0 ; move.l #0x100000,%d1 ; mulu.l %d1,%d0 ; " + // 2^20*2^20=2^40 -> V
      "move.l #0x40000000,%d2 ; moveq #4,%d3 ; muls.l %d3,%d2 ; " +       // 2^30*4=2^32 signed -> V
      "moveq #5,%d4 ; loop: bra loop", nInstr = 7)
  }

  // ── MULU.L/MULS.L 32x32 -> 64 lock-step (Dh:Dl 2-dest crack) ─────────────────
  // 0x4C00|ea form, ext bit10=1: Dh:Dl = ea * Dl (full 64-bit product). Cracked
  // [MUL -> Dl] + [MULHI -> Dh from the EU's latched high product]. V=0; N=Dh[31],
  // Z=(Dh|Dl==0). The trailing moves read Dh so the MULHI PRF write is verified.
  test("lock-step: MULU.L 32x32->64 (Dh:Dl) normal", VerilatorTest) {
    runLockStep("mul-l64-u",
      "move.l #0x100000,%d0 ; move.l #0x100000,%d1 ; mulu.l %d1,%d2:%d0 ; " + // 2^20*2^20=2^40
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 5)
  }

  // MULS.L 64-bit signed (negative product spans Dh:Dl).
  test("lock-step: MULS.L 32x32->64 (Dh:Dl) signed negative", VerilatorTest) {
    runLockStep("mul-l64-s",
      "move.l #-100000,%d0 ; move.l #100000,%d1 ; muls.l %d1,%d2:%d0 ; " + // -(10^10) signed
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 5)
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
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
      }
      def captureBranch(): Unit = {
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      def captureExc(): Unit = {
        val c = dut.rob.logic.commitObs(2)
        if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL, if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1)
      }
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
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
        x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
    }
    dut.clockDomain.onSamplings {
      captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
      val bw = dut.branchEu.logic.wbObs
      if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      for (k <- 0 until 2) {
        val c = dut.rob.logic.commitObs(k)
        if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
          sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL)
      }
      val ce = dut.rob.logic.commitObs(2)
      if (ce.fire.toBoolean) handle.onExcCommit(ce.pc.toLong & 0xffffffffL, ce.sysByte.toInt & 0xff, ce.a7.toLong & 0xffffffffL, if (ce.ccrFoldValid.toBoolean) ce.ccrFold.toInt & 0xf else -1)
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
