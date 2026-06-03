package m68k040.lockstep

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.{IdentityTranslationPlugin, DIdentityTranslationPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.RedirectService
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
      rob.logic.completion(0).valid   := eu0.completion.valid
      rob.logic.completion(0).payload := eu0.completion.payload
      rob.logic.completion(1).valid   := eu1.completion.valid
      rob.logic.completion(1).payload := eu1.completion.payload

      // ── LS cluster wiring (mirrors top/FullCoreSynth.BackendWiringPlugin) ──
      // LS issue port (3) -> LS EU. Its completion is BOTH a ROB completion (port
      // 2) AND the IQ dynamic-wakeup broadcast (variant A: a load's dependents wake
      // when the load's data is actually ready).
      lsEu.issue << iq.issue(3)
      rob.logic.completion(2).valid   := lsEu.completion.valid
      rob.logic.completion(2).payload := lsEu.completion.payload
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

      // ── Commit-time mispredict redirect fan-out (registered doFlush pulse) ──
      val doFlush = host[RedirectService].doFlush
      val flushPc = host[RedirectService].flushPc
      iq.flushPort := doFlush                                  // IQ clear
      host[DecodeStage].logic.pipeFlush := doFlush             // FE skid (decode->rename)
      host[RenameStage].logic.pipeFlush := doFlush             // FE skid (rename->dispatch)
      // RAT-rollback flush (rename.flushPort) is already driven by the ROB
      // (rc.flushPort := flushing). Fetch redirect to the resolved target:
      val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
      faRedir.valid   := doFlush
      faRedir.payload := flushPc
    }
  }

  /** Full-core DUT: the entire frontend+backend chain. The I-cache AXI master,
    * FetchAlign redirect/resume, EU wbObs and ROB commitObs surface for the sim. */
  class FullCoreDut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
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
      new IdentityTranslationPlugin,
      new DIdentityTranslationPlugin,
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
  def runLockStep(name: String, src: String, nInstr: Int = -1, checkMem: Seq[Long] = Seq.empty,
                  checkSpan: Int = 4): Unit = {
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
            println(s"[$name] COMMIT robId=${c.robId.toInt} pc=0x${(c.pc.toLong & 0xffffffffL).toHexString} (wbSeen=$wbCount)")
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL)
          }
        }
      }

      // Attach the program to the I-cache AXI.
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)

      // Attach a behavioral read/write memory to the D-cache AXI (separate image,
      // zeroed; the programs store before they load, so no data preload needed).
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)

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
        for (addr <- checkAddrs) {
          val expected = oracleMem.getOrElse(addr, 0) & 0xff
          val got = dmem.peekByte(addr)
          assert(got == expected,
            f"[$name] memory mismatch at 0x$addr%08x: dut=0x$got%02x oracle=0x$expected%02x")
        }
      }
    }
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
    // CCR is 0 in both DUT and oracle (stores/loads do not compute CCR in this
    // slice; matching today's aligned store lock-step convention).
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
}
