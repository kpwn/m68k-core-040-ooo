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

/** Task 4: interrupt delivery via the commit-side exception FSM.
  *
  * An interrupt at a macro-instruction boundary stacks a format-$0 frame {SR, head
  * instruction PC, curVec<<2}, fetches mem[VBR + curVec*4], redirects to it, and
  * the committed SR after entry has I-mask = level, S = 1, T = 0. Both autovector
  * (24+level) and vectored (iackVector) are exercised.
  */
class InterruptEntrySpec extends AnyFunSuite {

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
    val intCtrl = new InterruptControlPlugin
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val dtlb = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val wire = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), intCtrl, rsrc, drv, rob, csink, tsink, dtlb, dcache, wire)) }
  }

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0): Unit = {
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
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.isCondTrap #= false; u.sswInstr #= false; u.faultAddr #= 0
    // Line-4 ops added needsSupervisor (Track C privViolation) + sysOp/sysKind/sysReadDir
    // (Track D serializing system ops) to RenamedUop — drive inert here, else they read
    // garbage and spuriously assert privViolation/sysOpStore, blocking interruptPending.
    u.needsSupervisor #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.firstOfInstr #= true
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    dut.intCtrl.logic.iplIn #= 0
    dut.intCtrl.logic.iackAvec #= false
    dut.intCtrl.logic.iackVector #= 0
    pokeRu(dut.rsrc.logic.src.payload(0), valid = false)
    pokeRu(dut.rsrc.logic.src.payload(1), valid = false)
    cd.waitSampling(3)
  }

  def pokeBE32(dmem: BehavioralMemAgent, a: Long, w: Long): Unit =
    for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def peekBE16(dmem: BehavioralMemAgent, a: Long): Int =
    ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
  def peekBE32(dmem: BehavioralMemAgent, a: Long): Long =
    ((peekBE16(dmem, a).toLong << 16) | peekBE16(dmem, a + 2)) & 0xffffffffL

  /** Drive an interrupt entry and verify the frame + redirect + committed SR. */
  def runEntry(name: String, level: Int, avec: Boolean, vectorIn: Int, mask: Int,
               expVec: Int): Unit = {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      // Attach before the first clock edge so AXI R/B cannot power up as a fake
      // response and satisfy the cache's untagged exception-store acknowledgement.
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)

      val ssp0 = 0x00100000L
      val vbr  = 0L
      val handler = 0x40000000L + (expVec.toLong * 0x100L)
      dut.rob.logic.exc.ss.isp #= ssp0
      dut.rob.logic.exc.ss.vbr #= vbr
      // srSys: S=1 (bit5), mask=`mask`, T=0.
      dut.rob.logic.exc.ss.srSys #= (0x20 | (mask & 0x7))
      pokeBE32(dmem, vbr + expVec.toLong * 4, handler)
      cd.waitSampling(2)

      // alloc a NORMAL first-uop head at headPc; do NOT mark it complete (the
      // interrupt preempts it).
      val headPc = 0x40000010L
      pokeRu(dut.rsrc.logic.src.payload(0), pc = headPc)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // Raise the interrupt and confirm it is recognized (interruptPending pulses
      // for one cycle, then the exc FSM goes active and re-suppresses it).
      dut.intCtrl.logic.iplIn #= level
      dut.intCtrl.logic.iackAvec #= avec
      dut.intCtrl.logic.iackVector #= vectorIn
      var sawPend = false; var pn = 0
      while (!sawPend && pn < 10) {
        if (dut.rob.logic.interruptPending.toBoolean) sawPend = true
        pn += 1; cd.waitSampling()
      }
      assert(sawPend, s"[$name] interruptPending must fire at the boundary")

      // Wait for the exception redirect to the handler.
      var n = 0; var redirPc = -1L
      while (redirPc < 0 && n < 400) {
        if (dut.rob.logic.doFlushReg.toBoolean) redirPc = dut.rob.logic.flushPcReg.toLong & 0xffffffffL
        n += 1; cd.waitSampling()
      }
      assert(redirPc == handler, f"[$name] redirect pc=0x$redirPc%x expected handler 0x$handler%x")
      cd.waitSampling(50)

      // Format-$0 frame at SSP-8.
      val base = ssp0 - 8
      val sr  = peekBE16(dmem, base)
      val pc  = peekBE32(dmem, base + 2)
      val fmt = peekBE16(dmem, base + 6)
      // stacked SR system byte = the PRE-entry SR (S=1, mask=mask, T=0).
      assert((sr & 0xff00) == ((0x20 | (mask & 0x7)) << 8),
        f"[$name] stacked SR=0x$sr%04x expected sys 0x${0x20 | (mask & 0x7)}%02x")
      assert(pc == headPc, f"[$name] stacked PC=0x$pc%x expected head 0x$headPc%x")
      assert(fmt == (expVec << 2), f"[$name] format/vector word=0x$fmt%04x expected 0x${expVec << 2}%04x")

      // Committed SR after entry: I-mask = level, S = 1, T = 0.
      val sys = dut.rob.logic.exc.ss.srSys.toInt & 0xff
      assert((sys & 0x7) == (level & 0x7), f"[$name] post-entry I-mask=0x${sys & 0x7}%x expected level 0x${level & 0x7}%x")
      assert(((sys >> 5) & 1) == 1, s"[$name] S must be set after entry")
      assert(((sys >> 6) & 3) == 0, s"[$name] T must be cleared after entry")
      // SSP decremented by 8 (format-$0 = 8 bytes).
      assert((dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL) == base, s"[$name] SSP must be SSP-8")
    }
  }

  test("autovector interrupt: format-$0, vec=24+level, I-mask:=level") {
    runEntry("avec", level = 3, avec = true, vectorIn = 0, mask = 2, expVec = 24 + 3)
  }

  test("vectored interrupt: format-$0, vec=iackVector, I-mask:=level") {
    runEntry("vectored", level = 4, avec = false, vectorIn = 0x45, mask = 1, expVec = 0x45)
  }

  test("NMI: level 7 through mask 7, autovector 31") {
    runEntry("nmi", level = 7, avec = true, vectorIn = 0, mask = 7, expVec = 24 + 7)
  }
}
