package m68k040.exception

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.cache.{DcachePlugin, DcacheService}
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RenameCommitSinkPlugin, CommitTraceSinkPlugin, DebugCommitSinkPlugin, DebugHaltReasonCode}
import m68k040.isa.{Cluster, Size}
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 3: commit-side exception entry FSM.
  *
  * At retire of a faulted head entry the ROB's ExceptionUnit (1) flushes younger
  * work, (2) sets S=1, banks A7->SSP, (3) stacks a 68040 format-$0 frame to
  * SSP-8 (SR, PC hi, PC lo, format/vector word) via D-cache stores, (4) loads
  * the handler vector mem[VBR+vec*4], and (5) redirects fetch to it.
  *
  * DUT: rename-source -> ROB (+ExceptionUnit) -> DcachePlugin (+D-identity TLB).
  * A wiring plugin connects the ExceptionUnit's D-cache ports straight to the
  * cache (no LS EU here). A BehavioralMemAgent backs the D-cache AXI; the test
  * preloads the vector table and reads the stacked frame back.
  */
class ExceptionEntrySpec extends AnyFunSuite {

  /** Wire the ExceptionUnit's D-cache request ports directly to the D-cache (no
    * LS EU contention in this unit DUT). */
  class ExcDcacheWiring extends FiberPlugin {
    val logic = during build new Area {
      val rob = host[RobPlugin]
      val dc  = host[DcacheService]
      val xlate = host[m68k040.services.DTranslationService]
      val exc = rob.logic.exc
      // Directed backpressure gate. The real full-core path returns D-cache ready
      // through LsEu; this unit DUT can hold that handshake closed and prove the
      // ExceptionUnit's registered store source does not emit a lossy pulse.
      val storeAllow = in(Bool())
      // Exception frame/vector cache commands are explicitly physical in this
      // slice; keep the otherwise-unused tagged DTLB service quiescent.
      xlate.req.valid              := False
      xlate.req.payload.vpn        := U(0, 20 bits)
      xlate.req.payload.supervisor := False
      xlate.req.payload.write      := False
      xlate.req.payload.token      := U(0, 8 bits)
      xlate.rsp.ready              := True
      dc.loadCmd.valid   := exc.dcLoadCmd.valid
      dc.loadCmd.payload := exc.dcLoadCmd.payload
      exc.dcLoadCmd.ready := dc.loadCmd.ready
      // Fault injection for the 2026-09-09 `DLoadRsp.fault` work: force the fault bit on
      // every load response the exception sequencer sees, without perturbing the D-cache
      // itself (a real AXI error on a cacheable refill would also trip the cache's own
      // diagnostic-fault channel and halt the core for a DIFFERENT reason, which would
      // mask the behaviour under test). Field-by-field, because SpinalHDL rejects a
      // same-scope field override of a just-assigned bundle.
      val injLoadFault = in(Bool())
      exc.dcLoadRsp.valid        := dc.loadRsp.valid
      // POISON the data alongside the fault bit -- a bus error returns an error response,
      // not valid data, and a test that leaves the data intact would understate what the
      // unfixed core does with it.
      exc.dcLoadRsp.payload.data := Mux(injLoadFault, B(0xBADF00D1L, 32 bits), dc.loadRsp.payload.data)
      exc.dcLoadRsp.payload.line := dc.loadRsp.payload.line
      exc.dcLoadRsp.payload.fault := dc.loadRsp.payload.fault || injLoadFault
      exc.dcLoadBusy        := dc.loadBusy
      // Mirror FullCoreSynth's halt fold so the double-fault halt is observable here.
      // Inert while `dblFault` is False, i.e. for every other test in this file.
      rob.logic.coreHaltedIn := exc.dblFault
      rob.logic.haltReasonIn := Mux(exc.dblFault,
        U(m68k040.socket.HaltReason.DOUBLE_FAULT, m68k040.socket.HaltReason.W bits),
        U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits))
      dc.store.valid   := exc.dcStore.valid && storeAllow
      dc.store.payload := exc.dcStore.payload
      exc.dcStore.ready := dc.store.ready && storeAllow
      exc.dcStoreAck   := dc.storeAck
      // 2026-09-18: inject a non-OKAY B response on the exception sequencer's FRAME-PUSH
      // stores only. Mirrors `injLoadFault` above and, like it, does NOT perturb the
      // D-cache itself -- a real AXI write error would also trip the cache's own
      // diagnostic-fault channel and halt the core for a DIFFERENT reason, masking the
      // behaviour under test. `dcStoreErr` is only consulted under `dcStoreAck`, so a
      // level-high injection is equivalent to erroring every frame word.
      val injStoreErr = in(Bool())
      exc.dcStoreErr   := dc.storeErr || injStoreErr
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val dsink = new DebugCommitSinkPlugin
    val dtlb = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val wire = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dsink, dtlb, dcache, wire)) }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0,
             faulted: Boolean = false, faultVector: Int = 0, isRte: Boolean = false): Unit = {
    u.valid #= valid
    u.pc #= pc; u.lenWords #= 1; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.INT; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0
    u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= faulted; u.faultVector #= faultVector; u.isRte #= isRte
    u.firstOfInstr #= true; u.lastOfInstr #= true
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    pokeRu(dut.rsrc.logic.src.payload(0), valid = false)
    pokeRu(dut.rsrc.logic.src.payload(1), valid = false)
    cd.waitSampling(3)
  }

  test("illegal instruction at retire stacks format-$0 frame, fetches vector, redirects") {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      dut.wire.logic.storeAllow #= false
      dut.wire.logic.injLoadFault #= false
      dut.wire.logic.injStoreErr #= false
      // Attach the AXI responder before the first clock edge. Constructing it only
      // after init's three samples left R/B valid and payload inputs undriven during
      // reset release; a random B response could then become a cache storeAck with no
      // accepted descriptor (and, if RESP happened nonzero, a bogus diagnostic fault).
      // The test must not rely on simulator power-up values to prove exception entry.
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)
      var acceptedStores = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.storePort.valid.toBoolean &&
              dut.dcache.logic.storePort.ready.toBoolean) acceptedStores += 1
        }
      }

      // System state: SSP=0x00100000, VBR=0. Vector 4 handler at 0x40009000.
      val ssp0 = 0x00100000L
      val vbr  = 0L
      val handler = 0x40009000L
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.vbr #= vbr
      dut.rob.logic.haltExceptionMaskIn #= (BigInt(1) << 4)
      // srSys at reset = 0x27 (S=1). committed CCR seeded 0.
      // preload vector table: mem[VBR + 4*4] = handler. The 68k vector table is
      // big-endian (the cache LONG-extract assembles byte at offset 0 as the MSB).
      def pokeBE32(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeBE32(vbr + 4 * 4, handler)
      cd.waitSampling(2)

      // alloc a faulted uop (illegal -> vector 4) at pc 0x40000010.
      val faultPc = 0x40000010L
      pokeRu(dut.rsrc.logic.src.payload(0), pc = faultPc, faulted = true, faultVector = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      // Hold the first frame word at the arbitration boundary. A one-cycle Flow
      // producer would vanish here and leave E_STWAIT hung forever; the Stream
      // producer must instead keep every payload bit stable until fire.
      var waitStore = 0
      while (!dut.rob.logic.exc.dcStore.valid.toBoolean && waitStore < 80) {
        cd.waitSampling(); waitStore += 1
      }
      assert(dut.rob.logic.exc.dcStore.valid.toBoolean,
        "exception entry never presented its first frame-store command")
      val heldPaddr = dut.rob.logic.exc.dcStore.payload.paddr.toBigInt
      val heldData  = dut.rob.logic.exc.dcStore.payload.data.toBigInt
      val heldSize  = dut.rob.logic.exc.dcStore.payload.size.toEnum
      val heldMode  = dut.rob.logic.exc.dcStore.payload.cacheMode.toEnum
      val heldPrecise = dut.rob.logic.exc.dcStore.payload.precise.toBoolean
      for (_ <- 0 until 6) {
        assert(dut.rob.logic.exc.dcStore.valid.toBoolean,
          "exception frame-store valid dropped before acceptance")
        assert(dut.rob.logic.exc.dcStore.payload.paddr.toBigInt == heldPaddr &&
               dut.rob.logic.exc.dcStore.payload.data.toBigInt == heldData &&
               dut.rob.logic.exc.dcStore.payload.size.toEnum == heldSize &&
               dut.rob.logic.exc.dcStore.payload.cacheMode.toEnum == heldMode &&
               dut.rob.logic.exc.dcStore.payload.precise.toBoolean == heldPrecise,
          "exception frame-store payload changed while ready was low")
        assert(acceptedStores == 0, "a frame store crossed the closed ready gate")
        cd.waitSampling()
      }
      dut.wire.logic.storeAllow #= true

      // Wait for the exception redirect (fetch retarget to the handler).
      var n = 0; var redirPc = -1L
      var historySeen = false
      while (redirPc < 0 && n < 400) {
        if (dut.rob.logic.debugExceptionEntry.valid.toBoolean) {
          val event = dut.rob.logic.debugExceptionEntry.payload
          assert(event.vector.toInt == 4)
          assert((event.exceptionPc.toLong & 0xffffffffL) == faultPc)
          assert((event.faultAddress.toLong & 0xffffffffL) == 0L)
          assert((event.handlerPc.toLong & 0xffffffffL) == handler)
          historySeen = true
        }
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == handler, f"redirect pc=0x$redirPc%x expected handler 0x$handler%x")
      assert(historySeen, "completed exception entry must emit committed debug history")
      var haltWait = 0
      while (!dut.dsink.logic.effectiveHaltOut.toBoolean && haltWait < 10) {
        cd.waitSampling(); haltWait += 1
      }
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean,
        "masked completed exception entry did not reach effective halt")
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.EXCEPTION)
      assert(dut.dsink.logic.exceptionPendingOut.toBoolean)
      assert(dut.dsink.logic.haltExceptionVectorOut.toInt == 4)
      assert((dut.dsink.logic.haltExceptionPcOut.toLong & 0xffffffffL) == faultPc)
      assert((dut.dsink.logic.haltExceptionFaultAddressOut.toLong & 0xffffffffL) == 0L)
      assert(acceptedStores == 4,
        s"format-$$0 entry must accept exactly four frame words, saw $acceptedStores")

      // Let stores drain to memory.
      cd.waitSampling(50)

      // Frame at SSP-8: [base]=SR(16b), [base+2]=PC hi word, [base+4]=PC lo word,
      // [base+6]=format/vector word (= vector<<2 = 0x0010 for vector 4).
      val base = ssp0 - 8
      def peekBE16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def peekBE32(a: Long): Long = ((peekBE16(a).toLong << 16) | peekBE16(a + 2)) & 0xffffffffL
      val sr  = peekBE16(base)
      val pc  = peekBE32(base + 2)
      val fmt = peekBE16(base + 6)
      assert((sr & 0xff00) == 0x2700, f"stacked SR system byte wrong: 0x$sr%04x (expected 0x27xx)")
      assert(pc == faultPc, f"stacked PC=0x$pc%x expected 0x$faultPc%x")
      assert(fmt == (4 << 2), f"format/vector word=0x$fmt%04x expected 0x${4 << 2}%04x")

      // SSP decremented by 8; S still set (srSys bit5).
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == base, f"ssp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected 0x$base%x")
      assert(((dut.rob.logic.exc.ss.srSys.toInt >> 5) & 1) == 1, "S must be set after entry")
    }
  }
  // ── 2026-09-09: the VECTOR FETCH must not read past the end of its D-cache line ──
  //
  // `E_VECREQ` reads `VBR + vector*4` as ONE LONG straight into `dcache.loadCmd`, so it
  // never sees the LS EU's AGU cross-line splitter. `DcacheByteLane.extract` indexes the
  // 16-byte line with a 4-BIT offset, so a LONG at line offset 13/14/15 WRAPS its last
  // 1/2/3 bytes back to the line's own HEAD: the core would redirect to an address built
  // partly out of unrelated memory. `VBR + vector*4` is 16-byte aligned only when VBR is,
  // and the 68040's MOVEC to VBR takes all 32 bits, so `VBR mod 16` in {13, 14, 15} makes
  // every vector fetch straddle. Architecturally legal; never produced by Mac OS, which is
  // why this is a latent hole and not the p164-p167 boot defect.
  //
  // FAIL-BEFORE / PASS-AFTER, measured on this DUT with the split forced off. Decoy
  // 0xDEADBEEF at the head of the line the vector word starts in, handler 0x40009000 at
  // VBR+16:
  //   VBR mod 16 = 12 -> 0x40009000  (LONG ends exactly at the line boundary: the
  //                                   non-crossing CONTROL, and proof the split is not
  //                                   applied blanket)
  //   VBR mod 16 = 13 -> 0x4000903f / 0x40009093 / ... -- the trailing byte is GARBAGE
  //                      and varies with the simulator seed
  // The garbage (rather than the decoy's 0xDE) is itself informative: this DUT has no
  // `CacheControlService`, so the exception sequencer's loads run CACHE-INHIBITED and the
  // wrapped byte is taken from the never-filled remainder of the miss line rather than
  // from resident data. Either way the core redirects to an address it never read.
  // With task A's tripwire also live in this DUT (`M68kSim()` implies `.includeSimulation`),
  // an unfixed core additionally dies on the `extract` assertion itself -- that is how this
  // fail-before was run: the assertion had to be silenced before the wrong PC was visible.
  test("vector fetch straddling a 16-byte line (misaligned VBR) reads the whole longword") {
    val compiled = M68kSim().withVerilator.compile(new Dut)
    // 12 = non-crossing control; 13/14/15 = the three LONG straddle shapes (3+1, 2+2, 1+3).
    for (vbrLow <- Seq(12, 13, 14, 15)) {
      compiled.doSim(s"vbrLow$vbrLow") { dut =>
        val cd = dut.clockDomain
        dut.wire.logic.storeAllow #= true
        dut.wire.logic.injLoadFault #= false
        dut.wire.logic.injStoreErr #= false
        val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
        cd.forkStimulus(10)
        init(dut, cd)

        val ssp0    = 0x00100000L
        val vbr     = 0x00002000L + vbrLow
        val handler = 0x40009000L
        val vecAddr = vbr + 4 * 4                 // vector 4 (illegal instruction)
        dut.rob.logic.exc.ss.isp #= ssp0
        dut.rob.logic.exc.ss.vbr #= vbr
        dut.rob.logic.haltExceptionMaskIn #= 0
        def pokeBE32(a: Long, w: Long): Unit =
          for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
        pokeBE32(vecAddr & ~0xfL, 0xDEADBEEFL)    // decoy at the line head
        pokeBE32(vecAddr, handler)
        cd.waitSampling(2)

        val faultPc = 0x40000010L
        pokeRu(dut.rsrc.logic.src.payload(0), pc = faultPc, faulted = true, faultVector = 4)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
        dut.rsrc.logic.src.valid #= false
        cd.waitSampling()
        dut.rob.logic.completion(0).valid #= true
        dut.rob.logic.completion(0).payload #= 0
        cd.waitSampling()
        dut.rob.logic.completion(0).valid #= false

        var n = 0; var redirPc = -1L
        while (redirPc < 0 && n < 600) {
          if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
          n += 1; cd.waitSampling()
        }
        assert(redirPc == handler,
          f"VBR mod 16 = $vbrLow%d: redirect pc=0x$redirPc%x expected handler 0x$handler%x " +
          f"(vector word at 0x$vecAddr%x, line offset ${(vecAddr & 0xf).toInt}%d)")
      }
    }
  }

  // ── 2026-09-09: a BUS ERROR on the handler-vector read is a DOUBLE FAULT ─────────
  //
  // `DLoadRsp` has always carried a `fault` bit and the exception sequencer never looked
  // at it, so E_VECWAIT consumed a faulting vector read exactly like a good one and the
  // core redirected to whatever data rode along with the error response. On a real 68040
  // a bus error taken while the processor is ALREADY in exception processing is a double
  // bus fault and the part HALTS (M68040UM S8.4.2); this core had no double-fault path at
  // all, which is what turns one bad vector read into an unbounded re-fault loop that
  // marches the supervisor stack down through video memory.
  //
  // FAIL-BEFORE (measured, with the E_VECWAIT fault arm forced off): `dblFault` never
  // rises, nothing halts, and the core redirects to the poisoned response data as if it
  // were a handler address.
  // PASS-AFTER: no redirect at all, `dblFault` set, `coreHalted` with reason
  // DOUBLE_FAULT, and OFF_DBL_FAULT_PC / OFF_DBL_FAULT_VEC's backing registers carrying
  // the faulting instruction's PC and the vector number.
  //
  // The no-fault control is the first test in this file: same DUT, `injLoadFault` low,
  // redirect to the handler and no halt.
  test("a bus error on the vector fetch is a DOUBLE FAULT: halt, no redirect, capture PC+vector") {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      dut.wire.logic.storeAllow #= true
      dut.wire.logic.injLoadFault #= true      // every exc-sequencer load response faults
      dut.wire.logic.injStoreErr #= false
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)

      val ssp0    = 0x00100000L
      val handler = 0x40009000L
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.vbr #= 0L
      dut.rob.logic.haltExceptionMaskIn #= 0
      // A perfectly good vector table -- the point is that the READ of it errors, so the
      // core must NOT reach this value.
      for (i <- 0 until 4) dmem.pokeByte(4 * 4 + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)
      cd.waitSampling(2)

      val faultPc = 0x40000010L
      pokeRu(dut.rsrc.logic.src.payload(0), pc = faultPc, faulted = true, faultVector = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      var n = 0; var redirPc = -1L; var sawDbl = false
      while (n < 600 && !sawDbl) {
        if (dut.rob.logic.doFlushReg.toBoolean && redirPc < 0)
          redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        if (dut.rob.logic.exc.dblFault.toBoolean) sawDbl = true
        n += 1; cd.waitSampling()
      }
      assert(sawDbl, "a faulting vector fetch must raise dblFault")
      // Give the halt a few cycles to land, and prove no LATE redirect sneaks out.
      cd.waitSampling(20)
      assert(redirPc < 0 && !dut.rob.logic.doFlushReg.toBoolean,
        f"a double fault must NOT redirect (saw pc=0x$redirPc%x)")
      assert(dut.rob.logic.coreHalted.toBoolean, "a double fault must halt the core")
      assert(dut.rob.logic.haltReason.toInt == m68k040.socket.HaltReason.DOUBLE_FAULT,
        s"halt reason ${dut.rob.logic.haltReason.toInt} expected DOUBLE_FAULT")
      assert((dut.rob.logic.exc.dblFaultPc.toLong & 0xffffffffL) == faultPc,
        f"OFF_DBL_FAULT_PC capture 0x${dut.rob.logic.exc.dblFaultPc.toLong}%x expected 0x$faultPc%x")
      assert(dut.rob.logic.exc.dblFaultVec.toInt == 4,
        s"OFF_DBL_FAULT_VEC capture ${dut.rob.logic.exc.dblFaultVec.toInt} expected 4")
    }
  }

  // ── 2026-09-18 race audit: a bus error on the FRAME PUSH was silently swallowed ──
  //
  // `DcacheService.storeErr` (a one-cycle pulse alongside `storeAck` on a non-OKAY AXI
  // B response) has existed since task P1.4, but the exception sequencer had no input
  // for it: `E_STWAIT` consulted `dcStoreAck` ALONE, and DcachePlugin raises `storeAck`
  // on a bus-errored write exactly as on a good one. So a frame-push that bus-errored
  // advanced to the next frame word, then to the vector fetch, and the handler ran on a
  // PARTIALLY WRITTEN frame -- no vector, no halt, no diagnostic at all. The vector
  // FETCH side has had `E_DBLFAULT` since 2026-09-09; the frame PUSH side had nothing.
  //
  // This is the exact negative control for that: `injLoadFault` is LOW here, so the
  // vector table read would succeed -- the ONLY thing wrong is the frame store. That is
  // what separates this test from the vector-fetch double-fault test above; without the
  // fix this test reaches the handler cleanly.
  test("a bus error on the FRAME PUSH is a DOUBLE FAULT: halt, no redirect, no vector") {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      dut.wire.logic.storeAllow #= true
      dut.wire.logic.injLoadFault #= false     // the vector read is HEALTHY
      dut.wire.logic.injStoreErr #= true       // ...the frame push is not
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)

      val ssp0    = 0x00100000L
      val handler = 0x40009000L
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.vbr #= 0L
      dut.rob.logic.haltExceptionMaskIn #= 0
      // A perfectly good vector-4 entry. The core must NOT reach it: the frame it would
      // be returning through was never successfully written.
      for (i <- 0 until 4) dmem.pokeByte(4 * 4 + i, ((handler >> (8 * (3 - i))) & 0xff).toInt)
      cd.waitSampling(2)

      val faultPc = 0x40000010L
      pokeRu(dut.rsrc.logic.src.payload(0), pc = faultPc, faulted = true, faultVector = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      var n = 0; var redirPc = -1L; var sawDbl = false
      while (n < 600 && !sawDbl) {
        if (dut.rob.logic.doFlushReg.toBoolean && redirPc < 0)
          redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        if (dut.rob.logic.exc.dblFault.toBoolean) sawDbl = true
        n += 1; cd.waitSampling()
      }
      assert(sawDbl,
        "a bus error while STACKING the exception frame must raise dblFault -- without " +
        "it the sequencer walks on to the vector fetch and the handler runs on a " +
        "partially written frame")
      cd.waitSampling(20)
      assert(redirPc < 0 && !dut.rob.logic.doFlushReg.toBoolean,
        f"a double fault must NOT redirect (saw pc=0x$redirPc%x -- that is the handler " +
        "being entered over a half-written frame)")
      assert(dut.rob.logic.coreHalted.toBoolean, "a frame-push double fault must halt the core")
      assert(dut.rob.logic.haltReason.toInt == m68k040.socket.HaltReason.DOUBLE_FAULT,
        s"halt reason ${dut.rob.logic.haltReason.toInt} expected DOUBLE_FAULT")
      assert((dut.rob.logic.exc.dblFaultPc.toLong & 0xffffffffL) == faultPc,
        f"OFF_DBL_FAULT_PC capture 0x${dut.rob.logic.exc.dblFaultPc.toLong}%x expected 0x$faultPc%x")
      assert(dut.rob.logic.exc.dblFaultVec.toInt == 4,
        s"OFF_DBL_FAULT_VEC capture ${dut.rob.logic.exc.dblFaultVec.toInt} expected 4")
      // E_REDIR is where the new SSP is committed, and it is never reached -- so the
      // stack pointer must still be exactly where it was.
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == ssp0,
        f"isp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected the untouched 0x$ssp0%x")
    }
  }
}
