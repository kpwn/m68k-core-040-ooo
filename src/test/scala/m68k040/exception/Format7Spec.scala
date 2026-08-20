package m68k040.exception

import m68k040.{M68kParams, M68kSim, VerilatorTest}
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

/** Task 2: format-$7 access-fault frame in the commit-side exception FSM.
  *
  * At retire of a vector-2 (access-fault) head entry the ExceptionUnit stacks a
  * 68040 format-$7 frame (30 words / 0x3C bytes) to SSP-60, matching MAME's
  * m68ki_stack_frame_0111 byte-for-byte:
  *   [+0x00]=SR [+0x02]=PC [+0x06]=0x7000|(vec<<2) [+0x08]=EA [+0x0c]=SSW
  *   [+0x0e..0x12]=0 [+0x14]=faultAddr [+0x18..0x3a]=0
  * then fetches mem[VBR+8] and redirects. The vector-2 fault + faultAddr + SSW
  * attrs are supplied via lsFaultCompletion (the LS EU's path, Task 1).
  */
class Format7Spec extends AnyFunSuite {

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
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
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
    val wire = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dtlb, dcache, wire)) }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.LS; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false; u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    dut.rob.logic.lsFaultCompletion.valid #= false
    pokeRu(dut.rsrc.logic.src.payload(0), valid = false)
    pokeRu(dut.rsrc.logic.src.payload(1), valid = false)
    cd.waitSampling(3)
  }

  test("access fault (vector 2) at retire stacks format-$7 frame, fetches vector 2, redirects", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)

      val ssp0 = 0x00100000L
      val vbr  = 0L
      val handler = 0x40009000L
      val faultVA = 0x2000L
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.vbr #= vbr
      def pokeBE32(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeBE32(vbr + 2 * 4, handler)   // vector 2 (access fault) @ VBR+8
      cd.waitSampling(2)

      // alloc one LS uop at pc 0x40000010 (robId 0).
      val faultPc = 0x40000010L
      pokeRu(dut.rsrc.logic.src.payload(0), pc = faultPc)
      dut.rsrc.logic.src.valid #= true; dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      // complete it normally + drive the lsFaultCompletion (supervisor WRITE fault).
      dut.rob.logic.completion(2).valid #= true; dut.rob.logic.completion(2).payload #= 0
      dut.rob.logic.lsFaultCompletion.valid #= true
      dut.rob.logic.lsFaultCompletion.payload.robId #= 0
      dut.rob.logic.lsFaultCompletion.payload.faultAddr #= BigInt(faultVA)
      dut.rob.logic.lsFaultCompletion.payload.write #= true
      dut.rob.logic.lsFaultCompletion.payload.sizeBits #= 2
      dut.rob.logic.lsFaultCompletion.payload.supervisor #= true
      cd.waitSampling()
      dut.rob.logic.completion(2).valid #= false
      dut.rob.logic.lsFaultCompletion.valid #= false

      // Wait for the exception redirect to the handler.
      var n = 0; var redirPc = -1L
      while (redirPc < 0 && n < 600) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == handler, f"redirect pc=0x$redirPc%x expected handler 0x$handler%x")
      cd.waitSampling(80)   // let all 30 frame-word stores drain

      // Frame at SSP-60 (0x3C). Read it back big-endian and match MAME's $7 layout.
      val base = ssp0 - 60
      def peekBE16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def peekBE32(a: Long): Long = ((peekBE16(a).toLong << 16) | peekBE16(a + 2)) & 0xffffffffL
      val sr        = peekBE16(base + 0x00)
      val pc        = peekBE32(base + 0x02)
      val fmtVec    = peekBE16(base + 0x06)
      val ea        = peekBE32(base + 0x08)
      val ssw       = peekBE16(base + 0x0c)
      val faultAddr = peekBE32(base + 0x14)
      assert((sr & 0xff00) == 0x2700, f"stacked SR system byte 0x$sr%04x (expected 0x27xx)")
      assert(pc == faultPc, f"stacked PC=0x$pc%x expected 0x$faultPc%x")
      assert(fmtVec == 0x7008, f"format/vector word=0x$fmtVec%04x expected 0x7008")
      assert(ea == faultVA, f"effective address=0x$ea%x expected 0x$faultVA%x")
      assert(ssw == 0x0405, f"SSW=0x$ssw%04x expected 0x0405 (in_mmu|super-data|write)")
      assert(faultAddr == faultVA, f"fault address=0x$faultAddr%x expected 0x$faultVA%x")
      // Internal-field words must be ZERO (MAME no-writeback model).
      for (off <- Seq(0x0e, 0x10, 0x12, 0x18, 0x20, 0x28, 0x30, 0x38)) {
        assert(peekBE16(base + off) == 0, f"internal word @+0x$off%02x must be 0 (got 0x${peekBE16(base + off)}%04x)")
      }
      // SSP decremented by 60; S still set.
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == base, f"ssp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected 0x$base%x")
      assert(((dut.rob.logic.exc.ss.srSys.toInt >> 5) & 1) == 1, "S must be set after entry")
    }
  }
}
