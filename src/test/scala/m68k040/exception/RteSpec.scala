package m68k040.exception

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.cache.{DcachePlugin, DcacheService}
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RenameCommitSinkPlugin, CommitTraceSinkPlugin}
import m68k040.isa.{Cluster, Size}
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 4: RTE (return-from-exception).
  *
  * RTE (0x4E73, decoded with isRte) retires serializing -> the ExceptionUnit pops
  * the format-$0 frame at SSP: restore SR (incl. S -> may re-bank A7 to USP),
  * restore PC, SSP += 8, and redirect fetch to the restored PC.
  */
class RteSpec extends AnyFunSuite {

  class ExcDcacheWiring extends FiberPlugin {
    val logic = during build new Area {
      val rob = host[RobPlugin]
      val dc  = host[DcacheService]
      val xlate = host[m68k040.services.DTranslationService]
      val exc = rob.logic.exc
      xlate.req.valid := False
      xlate.req.payload.vpn := U(0, 20 bits)
      xlate.req.payload.supervisor := False
      xlate.req.payload.write := False
      xlate.req.payload.token := U(0, 8 bits)
      xlate.rsp.ready := True
      dc.loadCmd.valid   := exc.dcLoadCmd.valid
      dc.loadCmd.payload := exc.dcLoadCmd.payload
      exc.dcLoadCmd.ready := dc.loadCmd.ready
      // 2026-09-09: force the fault bit on every load response the exception sequencer
      // sees, WITHOUT perturbing the D-cache (a real AXI error on a cacheable refill also
      // trips the cache's own diagnostic channel, which would halt the core for a
      // different reason and mask the behaviour under test). Field-by-field, because
      // SpinalHDL rejects a same-scope field override of a just-assigned bundle.
      val injLoadFault = in(Bool())
      exc.dcLoadRsp.valid         := dc.loadRsp.valid
      // POISON the data alongside the fault bit -- a bus error returns an error response,
      // not valid data, and a test that leaves the data intact would understate what the
      // unfixed core does with it.
      exc.dcLoadRsp.payload.data  := Mux(injLoadFault, B(0xBADF00D1L, 32 bits), dc.loadRsp.payload.data)
      exc.dcLoadRsp.payload.line  := dc.loadRsp.payload.line
      exc.dcLoadRsp.payload.fault := dc.loadRsp.payload.fault || injLoadFault
      exc.dcLoadBusy        := dc.loadBusy
      dc.store.valid   := exc.dcStore.valid
      dc.store.payload := exc.dcStore.payload
      exc.dcStore.ready := dc.store.ready
      exc.dcStoreAck   := dc.storeAck
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
    val dtlb = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val anchor = new AnchorPlugin
    val wire = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dtlb, dcache, anchor, wire)) }
  }

  /** Anchors USP / A7 as top outputs so they are not pruned (their only on-chip
    * consumer in this unit DUT is the bank mux); the test reads + pokes them. */
  class AnchorPlugin extends FiberPlugin {
    val logic = during build new Area {
      val uspOut = out UInt (32 bits)
      val a7Out  = out UInt (32 bits)
      uspOut := host[RobPlugin].logic.exc.ss.usp
      a7Out  := host[RobPlugin].logic.exc.ss.a7
    }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0, isRte: Boolean = false): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.INT; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= isRte
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

  test("RTE pops format-$0 frame: restore SR + PC, SSP+=8, redirect; S->0 re-banks A7 to USP") {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      // The AXI responder must own R/B inputs before reset-release clocks. Attaching
      // it after init leaves them undefined for three samples, which can fabricate a
      // store acknowledgement despite RTE having accepted no store descriptor.
      dut.wire.logic.injLoadFault #= false
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)

      // SSP points at a preloaded format-$0 frame. The frame's SR has S=0 (return
      // to USER mode) so RTE must re-bank A7 to USP.
      val ssp0    = 0x00100000L
      val usp0    = 0x0000BEEFL
      val retSr   = 0x0004            // S=0, I=0, T=0, CCR=Z(bit2). user mode.
      val retPc   = 0x40001234L
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.usp #= usp0
      // build the frame at SSP (big-endian words): [0]=SR, [2]=PChi, [4]=PClo, [6]=fmt.
      def pokeBE16(a: Long, w: Int): Unit = { dmem.pokeByte(a, (w >> 8) & 0xff); dmem.pokeByte(a + 1, w & 0xff) }
      pokeBE16(ssp0 + 0, retSr)
      pokeBE16(ssp0 + 2, ((retPc >> 16) & 0xffff).toInt)
      pokeBE16(ssp0 + 4, (retPc & 0xffff).toInt)
      pokeBE16(ssp0 + 6, 0x0010)     // format/vector word (ignored on RTE)
      cd.waitSampling(2)

      // alloc an RTE uop, complete it -> retire triggers the RTE FSM.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x40000020L, isRte = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      // wait for the RTE redirect to the restored PC.
      var n = 0; var redirPc = -1L; var loadCmdFires = 0
      while (redirPc < 0 && n < 400) {
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean) loadCmdFires += 1
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == retPc, f"RTE redirect pc=0x$redirPc%x expected restored 0x$retPc%x")
      assert(loadCmdFires == 4,
        s"format-0 RTE must issue exactly four frame-word loads, observed $loadCmdFires")
      cd.waitSampling(5)

      // SR restored (system byte = retSr>>8 = 0x00, S=0); SSP += 8; A7 now USP.
      assert((dut.rob.logic.exc.ss.srSys.toInt & 0xff) == ((retSr >> 8) & 0xff),
        f"srSys=0x${dut.rob.logic.exc.ss.srSys.toInt}%02x expected 0x${(retSr >> 8) & 0xff}%02x")
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == ssp0 + 8,
        f"ssp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected 0x${ssp0 + 8}%x")
      // S=0 -> A7 reads USP
      assert(((dut.rob.logic.exc.ss.srSys.toInt >> 5) & 1) == 0, "S must be cleared (returned to user)")
      assert((dut.rob.logic.exc.ss.usp.toLong & 0xffffffffL) == usp0, "USP unchanged")
    }
  }

  // ── 2026-09-09: a BUS ERROR on an RTE frame-word read raises vector 2 ────────────
  //
  // `DLoadRsp.fault` was never checked on ANY exception-sequencer load, so a faulting
  // frame-word read was consumed exactly like a good one: RTE restored a garbage SR, a
  // garbage resume PC or a garbage frame format from whatever data came back with the
  // error response, and redirected there.
  //
  // Unlike the vector fetch -- where a fault is a DOUBLE fault and the part halts -- this
  // is an ORDINARY access fault: vector 2, format-$7. That is legal here precisely because
  // RTE's path up to `R_REDIR` is READ-ONLY, the same property the pre-existing
  // malformed-format (vector 14) and odd-PC (vector 3) re-entries already rely on. The
  // malformed frame is left untouched and a NEW frame is pushed below it, with the RTE
  // instruction's own PC stacked so a handler that repairs the mapping and RTEs out
  // re-attempts the same RTE.
  //
  // FAIL-BEFORE (measured, with the `frameWordWait` fault arm forced off): RTE builds its
  // resume PC out of the poisoned response data, gets an ODD PC, and the odd-PC guard
  // dispatches a vector-3 address error to VBR+12 -- uninitialised memory (0x5341c871 on
  // that seed). A bus error silently became a wild jump.
  // PASS-AFTER : redirect to the vector-2 handler, SSP -= 60, a format-$7 frame whose
  //              format/vector word is 0x7008 and whose fault-address field is the frame
  //              word that errored.
  //
  // The no-fault control is the first test in this file: same DUT, `injLoadFault` low.
  test("a bus error on an RTE frame-word read raises vector 2 (format-$7), not a garbage resume") {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      // Fault the frame reads; release once the vector-2 entry starts pushing its own
      // frame, so the vector-2 handler fetch itself succeeds (a faulting vector fetch is
      // the DOUBLE-fault path, covered by ExceptionEntrySpec, and would mask this one).
      dut.wire.logic.injLoadFault #= true
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)

      val ssp0     = 0x00100000L
      val rtePc    = 0x40000020L
      val handler2 = 0x40007700L          // vector 2 handler, at VBR+8 with VBR = 0
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.usp #= 0x0000BEEFL
      def pokeBE16(a: Long, w: Int): Unit = { dmem.pokeByte(a, (w >> 8) & 0xff); dmem.pokeByte(a + 1, w & 0xff) }
      // A perfectly good format-$0 frame -- the point is that READING it errors.
      pokeBE16(ssp0 + 0, 0x0004)
      pokeBE16(ssp0 + 2, 0x4000); pokeBE16(ssp0 + 4, 0x1234)
      pokeBE16(ssp0 + 6, 0x0010)
      pokeBE16(8, ((handler2 >> 16) & 0xffff).toInt)
      pokeBE16(10, (handler2 & 0xffff).toInt)
      cd.waitSampling(2)

      // Release the injected fault as soon as the vector-2 entry begins stacking.
      fork {
        while (!dut.rob.logic.exc.dcStore.valid.toBoolean) { cd.waitSampling() }
        dut.wire.logic.injLoadFault #= false
      }

      pokeRu(dut.rsrc.logic.src.payload(0), pc = rtePc, isRte = true)
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
      while (redirPc < 0 && n < 900) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == handler2,
        f"bus error on an RTE frame read must vector to 2's handler 0x$handler2%x, got 0x$redirPc%x")
      cd.waitSampling(60)   // let the 30 frame words drain to memory

      // format-$7 frame at SSP-60 (the RTE frame is left UNTOUCHED above it).
      val base = ssp0 - 60
      def peekBE16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def peekBE32(a: Long): Long = ((peekBE16(a).toLong << 16) | peekBE16(a + 2)) & 0xffffffffL
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == base,
        f"ssp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected the format-7 base 0x$base%x")
      assert(peekBE16(base + 6) == 0x7008,
        f"format/vector word 0x${peekBE16(base + 6)}%04x expected 0x7008 (format 7, vector 2)")
      assert(peekBE32(base + 2) == rtePc,
        f"stacked PC 0x${peekBE32(base + 2)}%x expected the RTE's own PC 0x$rtePc%x")
      // Fault address (format-$7 words 10/11, i.e. base+20) = the frame word that errored,
      // which is the FIRST one read: the SR at the frame base.
      assert(peekBE32(base + 20) == ssp0,
        f"fault address 0x${peekBE32(base + 20)}%x expected the failing frame word 0x$ssp0%x")
      // S stays set, and NOTHING of the aborted RTE was applied.
      assert(((dut.rob.logic.exc.ss.srSys.toInt >> 5) & 1) == 1,
        "the aborted RTE must not have restored the frame's S=0")
    }
  }
}
