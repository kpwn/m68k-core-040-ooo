package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.mmu.MmuControlPlugin
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import m68k040.services.DebugIntReadService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Owner side of the CPU_PRF_READ_TRIM borrow seam, with a sim-driven grant. It answers
  * from its own value table so the test can tell the borrowed port from the stub int RF. */
class DebugIntReadBorrowStubPlugin extends FiberPlugin with DebugIntReadService {
  private var req: Bool = null
  private var addr: UInt = null
  private var grant: Bool = null
  private var data: Bits = null
  override def debugIntReadReq: Bool = req
  override def debugIntReadAddr: UInt = addr
  override def debugIntReadGrant: Bool = grant
  override def debugIntReadData: Bits = data
  during setup {
    req = Bool(); req.allowOverride; req := False
    addr = UInt(6 bits); addr.allowOverride; addr := 0
    grant = Bool(); data = Bits(32 bits)
  }
  val logic = during build new Area {
    val grantDrive = in Bool()
    val values = in(Vec.fill(64)(Bits(32 bits)))
    val reqSeen = out(Bool())
    grant := req && grantDrive
    data := values(addr)
    reqSeen := req
  }
}

/** CPU_PRF_READ_TRIM: the debug live-integer read borrows the committed-A7 port and must
  * wait for its owner's grant, then return the word at the requested physical register.
  * With the flag OFF the debugger keeps its own port and the borrow seam is inert. */
class DebugIntReadBorrowSpec extends AnyFunSuite {
  val trim = m68k040.top.ShippingCoreConfig.prfReadTrim

  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin
    val mmu = new MmuControlPlugin
    val commit = new RenameCommitSinkPlugin
    val intRf = new DebugIntRfStubPlugin
    val nzvcRf = new DebugNzvcRfStubPlugin
    val xRf = new DebugXRfStubPlugin
    val maps = new DebugCommittedMapStubPlugin
    val memory = new DebugMemoryStubPlugin
    val borrow = new DebugIntReadBorrowStubPlugin
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 3)
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), mmu, rsrc, alloc, rob,
      commit, intRf, nzvcRf, xRf, maps, memory, borrow, dbg)) }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  test(s"live Dn read through the borrowed A7 port waits for the grant (trim=$trim)") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false; dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.memory.logic.quiescedDrive #= true
      dut.memory.logic.doneDrive #= false
      dut.memory.logic.errorDrive #= false
      dut.borrow.logic.grantDrive #= false
      for (i <- 0 until 64) {
        dut.borrow.logic.values(i) #= 0xb0000000L + i
      }
      for (i <- 0 until dut.intRf.logic.values.length) dut.intRf.logic.values(i) #= 0xc0000000L + i
      dut.maps.logic.intMap(3) #= 23
      cd.waitSampling(20)

      var done = false; var got = 0L
      var reqCycles = 0
      val reader = fork {
        got = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_DREG0 + 3 * 4); done = true
      }
      for (_ <- 0 until 60) { cd.waitSampling(); if (dut.borrow.logic.reqSeen.toBoolean) reqCycles += 1 }
      if (trim) {
        assert(!done, "the borrowed read completed without a grant")
        assert(reqCycles > 50, s"the request must be held while denied (held $reqCycles cycles)")
        dut.borrow.logic.grantDrive #= true
        reader.join()
        assert(got == 0xb0000000L + 23, f"borrowed read returned 0x$got%08x")
      } else {
        assert(done, "with the flag OFF the debugger owns its port and never waits")
        assert(reqCycles == 0)
        assert(got == 0xc0000000L + 23, f"own-port read returned 0x$got%08x")
      }
      // A second read with the grant open completes promptly.
      dut.borrow.logic.grantDrive #= true
      val v = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_DREG0 + 3 * 4)
      assert(v == (if (trim) 0xb0000000L + 23 else 0xc0000000L + 23))
    }
  }
}
