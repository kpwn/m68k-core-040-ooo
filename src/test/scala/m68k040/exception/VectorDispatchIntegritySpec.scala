package m68k040.exception

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.cache.{DcachePlugin, DcacheService}
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.decode.DecOp
import m68k040.rename.RenamedUop
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RenameCommitSinkPlugin,
                    CommitTraceSinkPlugin, DebugCommitSinkPlugin}
import m68k040.isa.{Cluster, Size}
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** VECTOR-DISPATCH INTEGRITY — "the redirect must be the FETCHED handler, never the
  * address the handler was fetched FROM".
  *
  * WHY THIS SPEC EXISTS. A hardware capture (Photoshop driving an F-LINE storm through
  * the ROM FPSP, machine halted at the fault) found the CPU executing at `0x0000002e`
  * with VBR = 0 — i.e. two bytes into the vector-11 slot at `0x2c` = `VBR + 11*4`, the
  * very address the F-line dispatch reads its handler from. The suspected mechanism was
  * that `ExceptionUnit.vecTarget` was ONE register serving TWO roles: the entry capture
  * wrote the fetch ADDRESS into it, `E_VECWAIT` overwrote it with the fetched DATA, and
  * `E_REDIR` read it as `redirectPc`. Any escape from the fetch that skipped the data
  * write would silently redirect the CPU INTO the vector table, and nothing in the RTL,
  * in simulation, or on silicon could tell that apart from a correct dispatch.
  *
  * The two roles are now two registers (`vecAddr` / `vecTarget`, plus `vecTargetVld`),
  * so a redirect can no longer carry the fetch address at all. This spec is the
  * regression that keeps it that way, and it is written to be a REPRODUCER first: it
  * sweeps the dispatch against the exact hardware shape (vector 11, VBR = 0, handler
  * word at line offset 12) and against the shapes most likely to break the fetch —
  * every 16-byte line offset a vector word can land on, back-to-back dispatches with no
  * idle cycle between them, and a storm of them.
  *
  * DUT: the standard commit-side unit core — rename-source -> ROB (+ExceptionUnit) ->
  * DcachePlugin (+D-identity TLB), same shape as `ExceptionEntrySpec`. */
class VectorDispatchIntegritySpec extends AnyFunSuite {

  class ExcDcacheWiring extends FiberPlugin {
    val logic = during build new Area {
      val rob   = host[RobPlugin]
      val dc    = host[DcacheService]
      val xlate = host[m68k040.services.DTranslationService]
      val exc   = rob.logic.exc
      xlate.req.valid              := False
      xlate.req.payload.vpn        := U(0, 20 bits)
      xlate.req.payload.supervisor := False
      xlate.req.payload.write      := False
      xlate.req.payload.token      := U(0, 8 bits)
      xlate.rsp.ready              := True
      dc.loadCmd.valid    := exc.dcLoadCmd.valid
      dc.loadCmd.payload  := exc.dcLoadCmd.payload
      exc.dcLoadCmd.ready := dc.loadCmd.ready
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy
      rob.logic.coreHaltedIn := exc.dblFault
      rob.logic.haltReasonIn := Mux(exc.dblFault,
        U(m68k040.socket.HaltReason.DOUBLE_FAULT, m68k040.socket.HaltReason.W bits),
        U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits))
      dc.store.valid    := exc.dcStore.valid
      dc.store.payload  := exc.dcStore.payload
      exc.dcStore.ready := dc.store.ready
      exc.dcStoreAck    := dc.storeAck
    }
  }

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val rsrc   = new RenameUopSourcePlugin
    val drv    = new RobAllocDriverPlugin
    val rob    = new RobPlugin
    val csink  = new RenameCommitSinkPlugin
    val tsink  = new CommitTraceSinkPlugin
    val dsink  = new DebugCommitSinkPlugin
    val dtlb   = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val wire   = new ExcDcacheWiring
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, dsink, dtlb, dcache, wire)) }
  }

  /** ONE Verilator build for the whole suite (see FsaveFrestoreSpec's note on
    * per-test rebuild sites OOMing the forked JVM). */
  private lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  def pokeRu(u: RenamedUop, valid: Boolean = true, pc: Long = 0,
             faulted: Boolean = false, faultVector: Int = 0): Unit = {
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
    u.faulted #= faulted; u.faultVector #= faultVector; u.isRte #= false
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

  /** Allocate one faulted uop and complete it, so it faults at retire. */
  def raise(dut: Dut, cd: ClockDomain, pc: Long, vector: Int): Unit = {
    pokeRu(dut.rsrc.logic.src.payload(0), pc = pc, faulted = true, faultVector = vector)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= false
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= 0
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= false
  }

  final case class Dispatch(redirPc: Long, handlerPc: Long, vector: Int,
                            vecTargetVld: Boolean, tableRedirect: Boolean, cycles: Int)

  /** Wait for the entry's redirect and capture everything the dispatch published in
    * the SAME cycle: the registered flush PC the frontend will actually use, the
    * ring's `handlerPc` (`obsHandlerPc`, driven off the same register), and the two
    * new integrity signals. */
  def awaitDispatch(dut: Dut, cd: ClockDomain, budget: Int = 800): Dispatch = {
    var n = 0
    var handlerPc = -1L
    var vector = -1
    var vld = false
    while (n < budget) {
      if (dut.rob.logic.debugExceptionEntry.valid.toBoolean) {
        handlerPc = dut.rob.logic.debugExceptionEntry.payload.handlerPc.toLong & 0xffffffffL
        vector    = dut.rob.logic.debugExceptionEntry.payload.vector.toInt
        vld       = dut.rob.logic.exc.vecTargetVld.toBoolean
      }
      if (dut.rob.logic.doFlushReg.toBoolean && handlerPc >= 0) {
        return Dispatch(dut.rob.logic.flushPcReg.toLong & 0xffffffffL, handlerPc, vector, vld,
                        dut.rob.logic.exc.vecTableRedirect.toBoolean, n)
      }
      n += 1; cd.waitSampling()
    }
    sys.error(s"exception entry never redirected within $budget cycles")
  }

  def pokeBE32(dmem: BehavioralMemAgent, a: Long, w: Long): Unit =
    for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  /** The failure shape under test, stated once: a dispatch whose redirect PC is the
    * vector-table slot it read the handler out of. */
  def checkDispatch(d: Dispatch, vbr: Long, vector: Int, handler: Long, what: String): Unit = {
    val slot = (vbr + vector * 4L) & 0xffffffffL
    assert(d.redirPc != slot,
      f"$what: dispatch redirected INTO THE VECTOR TABLE — redirect pc=0x${d.redirPc}%08x " +
      f"== VBR+vec*4 (VBR=0x$vbr%08x, vector=$vector). This is the hardware failure shape.")
    assert(d.redirPc == handler,
      f"$what: redirect pc=0x${d.redirPc}%08x, expected the fetched handler 0x$handler%08x " +
      f"(vector word at 0x$slot%08x)")
    assert(d.handlerPc == handler,
      f"$what: the exception ring recorded handler 0x${d.handlerPc}%08x, expected 0x$handler%08x")
    assert(d.vecTargetVld,
      f"$what: redirected with vecTargetVld LOW — E_REDIR was reached without a vector-fetch result")
    assert(!d.tableRedirect,
      f"$what: the vector-table-redirect tripwire fired on a correct dispatch")
  }

  // ── 1. The exact hardware shape, plus every line offset a vector word can occupy ──
  //
  // VBR = 0 / vector 11 IS the captured case: the handler word sits at 0x2c, line
  // offset 12 — the LAST offset a LONG can occupy without straddling, so it is also the
  // boundary case of `vecCrosses` (`> 12`). The other VBR values walk the vector word
  // across all four offsets it can take, including the three straddle shapes that read
  // the handler as four separate byte loads and reassemble it.
  test("a vector dispatch redirects to the FETCHED handler, never to VBR+vector*4") {
    val cases = Seq(
      (0x00000000L, 11, "hardware shape: F-line, VBR=0, handler word at 0x2c"),
      (0x00000000L,  4, "illegal instruction, VBR=0"),
      (0x00000000L,  2, "access fault, VBR=0"),
      (0x00002000L, 11, "F-line, aligned VBR"),
      (0x00002004L, 11, "F-line, VBR mod 16 = 4"),
      (0x0000200cL, 11, "F-line, VBR mod 16 = 12"),
      (0x0000200dL, 11, "F-line, VBR mod 16 = 13 (straddle 3+1)"),
      (0x0000200eL, 11, "F-line, VBR mod 16 = 14 (straddle 2+2)"),
      (0x0000200fL, 11, "F-line, VBR mod 16 = 15 (straddle 1+3)"))
    for (((vbr, vector, what), idx) <- cases.zipWithIndex) {
      compiled.doSim(s"dispatch_$idx") { dut =>
        val cd = dut.clockDomain
        val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
        cd.forkStimulus(10)
        init(dut, cd)
        val ssp0    = 0x00100000L
        val handler = 0x40009000L + vector * 0x100L
        val slot    = vbr + vector * 4L
        dut.rob.logic.exc.ss.isp #= ssp0
        dut.rob.logic.exc.ss.vbr #= vbr
        dut.rob.logic.haltExceptionMaskIn #= 0
        // A decoy at the head of the line the vector word starts in: if the fetch ever
        // reassembles from the wrong bytes this is what shows up in the redirect.
        pokeBE32(dmem, slot & ~0xfL, 0xDEADBEEFL)
        pokeBE32(dmem, slot, handler)
        cd.waitSampling(2)
        raise(dut, cd, pc = 0x40000010L, vector = vector)
        val d = awaitDispatch(dut, cd)
        assert(d.vector == vector, s"$what: ring vector ${d.vector} != $vector")
        checkDispatch(d, vbr, vector, handler, what)
      }
    }
  }

  // ── 2. A STORM of back-to-back dispatches, the shape the hardware was in ─────────
  //
  // Photoshop drove an F-LINE trap storm: 9 of the last 11 ring entries were vector 11.
  // Every entry re-arms the fetch address, re-pushes a frame and re-reads the SAME
  // vector word, so any state that leaks across episodes (a stale fetch cursor, a stale
  // result, a response claimed by the wrong episode) shows up here and nowhere else.
  // Each entry is raised as soon as the previous one has redirected — no idle gap.
  test("32 back-to-back F-line dispatches each redirect to the handler") {
    compiled.doSim("flineStorm") { dut =>
      val cd = dut.clockDomain
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)
      val vbr     = 0x00000000L
      val vector  = 11
      val handler = 0x4088d9feL      // the real Q700 ROM FPSP entry from the capture
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.vbr #= vbr
      dut.rob.logic.haltExceptionMaskIn #= 0
      pokeBE32(dmem, vbr + vector * 4L, handler)
      cd.waitSampling(2)
      for (i <- 0 until 32) {
        raise(dut, cd, pc = 0x4088e268L + i * 2, vector = vector)
        val d = awaitDispatch(dut, cd)
        checkDispatch(d, vbr, vector, handler, s"storm entry $i")
        // The SSP walks down 8 bytes per entry (nothing RTEs here); re-seat it so the
        // frame pushes stay inside the modelled memory for all 32 iterations.
        dut.rob.logic.exc.ss.isp #= 0x00100000L
        cd.waitSampling(2)
      }
    }
  }

  // ── 3. The silicon tripwire is NON-VACUOUS ──────────────────────────────────────
  //
  // Point a vector slot at another slot in the same table and the dispatch legitimately
  // redirects into [VBR, VBR+0x400): exactly the shape the tripwire exists to name on a
  // halted board. It must fire, and it must be sticky.
  test("the vector-table-redirect tripwire fires when a handler address is inside the table") {
    compiled.doSim("tripwire") { dut =>
      val cd = dut.clockDomain
      val dmem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      cd.forkStimulus(10)
      init(dut, cd)
      val vbr     = 0x00000000L
      val vector  = 11
      val handler = vbr + 0x2eL        // two bytes into the vector-11 slot: the halt PC
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.vbr #= vbr
      dut.rob.logic.haltExceptionMaskIn #= 0
      pokeBE32(dmem, vbr + vector * 4L, handler)
      cd.waitSampling(2)
      assert(!dut.rob.logic.exc.vecTableRedirect.toBoolean, "tripwire set before any dispatch")
      raise(dut, cd, pc = 0x4088e268L, vector = vector)
      val d = awaitDispatch(dut, cd)
      assert(d.redirPc == handler, f"redirect pc=0x${d.redirPc}%08x expected 0x$handler%08x")
      assert(d.tableRedirect, "the vector-table-redirect tripwire did NOT fire")
      cd.waitSampling(20)
      assert(dut.rob.logic.exc.vecTableRedirect.toBoolean, "the tripwire is not sticky")
      assert((dut.rob.logic.exc.vecTableRedirectPc.toLong & 0xffffffffL) == handler)
      assert(dut.rob.logic.exc.vecTableRedirectVec.toInt == vector)
    }
  }
}
