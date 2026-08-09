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
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy
      dc.store.valid   := exc.dcStore.valid && storeAllow
      dc.store.payload := exc.dcStore.payload
      exc.dcStore.ready := dc.store.ready && storeAllow
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
    val dcache = new DcachePlugin
    val wire = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dtlb, dcache, wire)) }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0,
             faulted: Boolean = false, faultVector: Int = 0, isRte: Boolean = false): Unit = {
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
    u.faulted #= faulted; u.faultVector #= faultVector; u.isRte #= isRte
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
      while (redirPc < 0 && n < 400) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == handler, f"redirect pc=0x$redirPc%x expected handler 0x$handler%x")
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
}
