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

/** Task 3: RTE from a format-$7 access-fault frame.
  *
  * RTE inspects the stacked format word (@base+6); for a $7 (top nibble 7) it
  * restores SR + PC and pops the 60-byte ($3C) frame (SSP += 60), then redirects
  * to the restored PC (= the faulting instruction's PC -> re-executes), matching
  * MAME's RTE case 7. (Format-$0 still pops 8 — see RteSpec.)
  */
class RteFormat7Spec extends AnyFunSuite {

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

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0, isRte: Boolean = false): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE; u.cluster #= Cluster.INT; u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0; u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false; u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false; u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false; u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= isRte
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

  test("RTE pops format-$7 frame: restore SR + PC, SSP += 60, redirect to faulting PC", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)

      // SSP points at a preloaded format-$7 frame. SR stays supervisor (S=1) — a $7
      // access fault was taken in supervisor; RTE returns to re-execute the access.
      val ssp0  = 0x00100000L
      val retSr = 0x2700            // S=1, I=7. supervisor.
      val retPc = 0x40000010L       // the faulting instruction PC (re-executed)
      val faultVA = 0x2000L
      dut.rob.logic.exc.ss.isp #= ssp0
      def pokeBE16(a: Long, w: Int): Unit = { dmem.pokeByte(a, (w >> 8) & 0xff); dmem.pokeByte(a + 1, w & 0xff) }
      def pokeBE32(a: Long, w: Long): Unit = { pokeBE16(a, ((w >> 16) & 0xffff).toInt); pokeBE16(a + 2, (w & 0xffff).toInt) }
      // Stack the full $7 frame (MAME layout) so the format word @+6 selects $7.
      pokeBE16(ssp0 + 0x00, retSr)
      pokeBE32(ssp0 + 0x02, retPc)
      pokeBE16(ssp0 + 0x06, 0x7008)        // format-$7 / vector 2
      pokeBE32(ssp0 + 0x08, faultVA)       // EA
      pokeBE16(ssp0 + 0x0c, 0x0405)        // SSW
      pokeBE32(ssp0 + 0x14, faultVA)       // fault address
      cd.waitSampling(2)

      // alloc an RTE uop, complete it -> retire triggers the RTE FSM.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x40000040L, isRte = true)
      dut.rsrc.logic.src.valid #= true; dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      var n = 0; var redirPc = -1L
      while (redirPc < 0 && n < 400) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == retPc, f"RTE redirect pc=0x$redirPc%x expected restored faulting PC 0x$retPc%x")
      cd.waitSampling(5)

      assert((dut.rob.logic.exc.ss.srSys.toInt & 0xff) == ((retSr >> 8) & 0xff),
        f"srSys=0x${dut.rob.logic.exc.ss.srSys.toInt}%02x expected 0x${(retSr >> 8) & 0xff}%02x")
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == ssp0 + 60,
        f"ssp=0x${dut.rob.logic.exc.ss.isp.toLong}%x expected 0x${ssp0 + 60}%x (popped 60-byte format-7 frame)")
      assert(((dut.rob.logic.exc.ss.srSys.toInt >> 5) & 1) == 1, "S still set (returned to supervisor)")
    }
  }
}
