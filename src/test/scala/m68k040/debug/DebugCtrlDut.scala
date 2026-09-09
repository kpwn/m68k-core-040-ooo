package m68k040.debug

import m68k040.services.{DebugCommitService, FrontendDebugMatchService}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Standalone host for `DebugCtrlPlugin`, following the same PluginHost-in-a-Component
  * pattern as `m68k040.mmu.MmuControlSpec.Dut`. `ParamPlugin` is deliberately absent:
  * `DebugCtrlPlugin` reads no `Global` key (spec section 0.9), so nothing would block on
  * it.
  *
  * `porCyclesArg` defaults to 4 so the debug power-on window is a handful of cycles in
  * simulation instead of the synthesis default of 16. */
class DebugCtrlDut(buildIdArg:   BigInt  = BigInt(0x12345678L),
                   porCyclesArg: Int     = 4,
                   stageArg:     Int     = 1,
                   enableArg:    Boolean = true,
                   withCommitStubArg: Boolean = false,
                   withFrontendStubArg: Boolean = false) extends Component {
  val db   = new Database
  val host = db on (new PluginHost)
  val commitStub: DebugCommitStubPlugin =
    if (withCommitStubArg) new DebugCommitStubPlugin else null
  val frontendStub: FrontendDebugMatchStubPlugin =
    if (withFrontendStubArg) new FrontendDebugMatchStubPlugin else null
  val dbg  = new DebugCtrlPlugin(buildId = buildIdArg, porCycles = porCyclesArg, stage = stageArg,
                                 enable = enableArg)
  val plugins: Seq[FiberPlugin] =
    Seq(Option(commitStub), Option(frontendStub), Some(dbg)).flatten
  db.on { host.asHostOf(plugins) }

  def axi: DbgAxiLite = dbg.logic.dbgAxi
}

class FrontendDebugMatchStubPlugin extends FiberPlugin with FrontendDebugMatchService {
  private var pcsWire: Vec[UInt] = null
  private var enablesWire: Bits = null
  private var skipOnceWire: Bits = null
  during setup {
    pcsWire = Vec(UInt(32 bits), 4); pcsWire.foreach { p => p.allowOverride; p := 0 }
    enablesWire = Bits(4 bits); enablesWire.allowOverride; enablesWire := 0
    skipOnceWire = Bits(4 bits); skipOnceWire.allowOverride; skipOnceWire := 0
  }
  val logic = during build new Area {
    val pcs = out(Vec(UInt(32 bits), 4)); pcs := pcsWire
    val enables = out(Bits(4 bits)); enables := enablesWire
    val skipOnce = out(Bits(4 bits)); skipOnce := skipOnceWire
    val skipConsumedDrive = Bits(4 bits); skipConsumedDrive := 0
  }
  override def configure(pcs: Vec[UInt], enables: Bits, skipOnce: Bits): Unit = {
    pcsWire := pcs; enablesWire := enables; skipOnceWire := skipOnce
  }
  override def skipConsumed: Bits = logic.skipConsumedDrive
}

/** Minimal test provider for DebugCtrlPlugin's real service boundary. Readback values
  * are pokeable registers; command outputs expose exactly what `request` receives. */
class DebugCommitStubPlugin extends FiberPlugin with DebugCommitService {
  private var effectiveHaltWire: Bool = null
  private var autoHaltWire: Bool = null
  private var stopWire: Bool = null
  private var resumeWire: Bool = null
  private var stepWire: Bool = null
  private var clearStickyWire: Bool = null
  private var haltAfterTargetWire: UInt = null
  private var haltAfterEpochWire: UInt = null
  private var haltAfterArmedWire: Bool = null
  private var haltAfterInvalidateWire: Bool = null
  private var haltExceptionMaskWire: Bits = null
  private var a7OddEnWire: Bool = null
  private var a7OddThreshWire: UInt = null
  private var pcRangeEnWire: Bool = null
  private var pcRangeLoWire: UInt = null
  private var pcRangeHiWire: UInt = null

  override def effectiveHalt: Bool = effectiveHaltWire
  override def autoHaltLatched: Bool = autoHaltWire
  override def haltReasonDebug: UInt = logic.haltReasonDrive
  override def livePc: UInt = logic.livePcDrive
  override def lastPc: UInt = logic.lastPcDrive
  override def macroCount: UInt = logic.macroCountDrive
  override def haltHitInstCount: UInt = logic.haltHitInstCountDrive
  override def haltAfterConsumed: Bool = logic.haltAfterConsumedDrive
  override def haltHitPc: UInt = logic.haltHitPcDrive
  override def breakpointHit: spinal.lib.Flow[UInt] = logic.breakpointHitDrive
  override def exceptionPending: Bool = logic.exceptionPendingDrive
  override def haltExceptionVector: UInt = logic.haltExceptionVectorDrive
  override def haltExceptionPc: UInt = logic.haltExceptionPcDrive
  override def haltExceptionFaultAddress: UInt = logic.haltExceptionFaultAddressDrive
  override def dblFaultPc:  UInt = logic.dblFaultPcDrive
  override def dblFaultVec: UInt = logic.dblFaultVecDrive
  override def haltKind:    UInt = logic.haltKindDrive
  override def configureA7OddHalt(enable: Bool, threshold: UInt): Unit = {
    a7OddEnWire := enable
    a7OddThreshWire := threshold
  }
  override def a7OddPc0:      UInt = logic.a7OddZero32
  override def a7OddPc1:      UInt = logic.a7OddZero32
  override def a7OddPc2:      UInt = logic.a7OddZero32
  override def a7OddValue:    UInt = logic.a7OddZero32
  override def a7OddEpisodes: UInt = logic.a7OddZero16
  override def configurePcRangeHalt(enable: Bool, lo: UInt, hi: UInt): Unit = {
    pcRangeEnWire := enable; pcRangeLoWire := lo; pcRangeHiWire := hi
  }
  override def pcRangePc0:   UInt = logic.a7OddZero32
  override def pcRangePc1:   UInt = logic.a7OddZero32
  override def pcRangePc2:   UInt = logic.a7OddZero32
  override def pcRangeCount: UInt = logic.a7OddZero16

  during setup {
    effectiveHaltWire = Bool()
    autoHaltWire = Bool()
    stopWire = Bool(); stopWire.allowOverride; stopWire := False
    resumeWire = Bool(); resumeWire.allowOverride; resumeWire := False
    stepWire = Bool(); stepWire.allowOverride; stepWire := False
    clearStickyWire = Bool(); clearStickyWire.allowOverride; clearStickyWire := False
    haltAfterTargetWire = UInt(64 bits); haltAfterTargetWire.allowOverride
    haltAfterTargetWire := U(0, 64 bits)
    haltAfterEpochWire = UInt(8 bits); haltAfterEpochWire.allowOverride
    haltAfterEpochWire := U(0, 8 bits)
    haltAfterArmedWire = Bool(); haltAfterArmedWire.allowOverride; haltAfterArmedWire := False
    haltAfterInvalidateWire = Bool(); haltAfterInvalidateWire.allowOverride
    haltAfterInvalidateWire := False
    haltExceptionMaskWire = Bits(256 bits); haltExceptionMaskWire.allowOverride
    haltExceptionMaskWire := 0
    a7OddEnWire = Bool(); a7OddEnWire.allowOverride; a7OddEnWire := False
    a7OddThreshWire = UInt(16 bits); a7OddThreshWire.allowOverride; a7OddThreshWire := U(0, 16 bits)
    pcRangeEnWire = Bool(); pcRangeEnWire.allowOverride; pcRangeEnWire := False
    pcRangeLoWire = UInt(32 bits); pcRangeLoWire.allowOverride; pcRangeLoWire := U(0, 32 bits)
    pcRangeHiWire = UInt(32 bits); pcRangeHiWire.allowOverride; pcRangeHiWire := U(0, 32 bits)
  }

  val logic = during build new Area {
    val effectiveHaltDrive = RegInit(False); effectiveHaltDrive.simPublic()
    val autoHaltDrive = RegInit(False); autoHaltDrive.simPublic()
    val haltReasonDrive = Reg(UInt(3 bits)) init 0; haltReasonDrive.simPublic()
    val livePcDrive = Reg(UInt(32 bits)) init 0; livePcDrive.simPublic()
    val lastPcDrive = Reg(UInt(32 bits)) init 0; lastPcDrive.simPublic()
    val macroCountDrive = Reg(UInt(64 bits)) init 0; macroCountDrive.simPublic()
    val haltHitInstCountDrive = Reg(UInt(64 bits)) init 0; haltHitInstCountDrive.simPublic()
    val haltAfterConsumedDrive = RegInit(False); haltAfterConsumedDrive.simPublic()
    val haltHitPcDrive = Reg(UInt(32 bits)) init 0; haltHitPcDrive.simPublic()
    val a7OddZero32 = U(0, 32 bits)
    val a7OddZero16 = U(0, 16 bits)
    val breakpointHitDrive = spinal.lib.Flow(UInt(2 bits))
    val breakpointHitValidDrive = RegInit(False); breakpointHitValidDrive.simPublic()
    val breakpointHitSlotDrive = Reg(UInt(2 bits)) init 0; breakpointHitSlotDrive.simPublic()
    breakpointHitValidDrive := breakpointHitValidDrive
    breakpointHitSlotDrive := breakpointHitSlotDrive
    breakpointHitDrive.valid := breakpointHitValidDrive
    breakpointHitDrive.payload := breakpointHitSlotDrive
    val exceptionPendingDrive = RegInit(False); exceptionPendingDrive.simPublic()
    val haltExceptionVectorDrive = Reg(UInt(8 bits)) init 0; haltExceptionVectorDrive.simPublic()
    val haltExceptionPcDrive = Reg(UInt(32 bits)) init 0; haltExceptionPcDrive.simPublic()
    val haltExceptionFaultAddressDrive = Reg(UInt(32 bits)) init 0
    haltExceptionFaultAddressDrive.simPublic()
    // 2026-09-09 double-bus-fault capture (OFF_DBL_FAULT_PC / OFF_DBL_FAULT_VEC).
    val dblFaultPcDrive  = Reg(UInt(32 bits)) init 0; dblFaultPcDrive.simPublic()
    val dblFaultVecDrive = Reg(UInt(8 bits)) init 0;  dblFaultVecDrive.simPublic()
    // 2026-09-09 fatal-halt attribution (OFF_HALT_KIND).
    val haltKindDrive = Reg(UInt(m68k040.socket.HaltReason.W bits)) init 0
    haltKindDrive.simPublic()
    effectiveHaltDrive := effectiveHaltDrive
    autoHaltDrive := autoHaltDrive
    haltReasonDrive := haltReasonDrive
    livePcDrive := livePcDrive
    lastPcDrive := lastPcDrive
    macroCountDrive := macroCountDrive
    haltHitInstCountDrive := haltHitInstCountDrive
    haltAfterConsumedDrive := haltAfterConsumedDrive
    haltHitPcDrive := haltHitPcDrive
    exceptionPendingDrive := exceptionPendingDrive
    haltExceptionVectorDrive := haltExceptionVectorDrive
    haltExceptionPcDrive := haltExceptionPcDrive
    haltExceptionFaultAddressDrive := haltExceptionFaultAddressDrive
    dblFaultPcDrive  := dblFaultPcDrive
    dblFaultVecDrive := dblFaultVecDrive
    haltKindDrive    := haltKindDrive
    effectiveHaltWire := effectiveHaltDrive
    autoHaltWire := autoHaltDrive

    val stopRequest = out(Bool())
    val resumeRequest = out(Bool())
    val stepRequest = out(Bool())
    val clearStickyRequest = out(Bool())
    val haltAfterTarget = out(UInt(64 bits))
    val haltAfterEpoch = out(UInt(8 bits))
    val haltAfterArmed = out(Bool())
    val haltAfterInvalidate = out(Bool())
    val haltExceptionMask = out(Bits(256 bits))
    stopRequest := stopWire
    resumeRequest := resumeWire
    stepRequest := stepWire
    clearStickyRequest := clearStickyWire
    haltAfterTarget := haltAfterTargetWire
    haltAfterEpoch := haltAfterEpochWire
    haltAfterArmed := haltAfterArmedWire
    haltAfterInvalidate := haltAfterInvalidateWire
    haltExceptionMask := haltExceptionMaskWire
  }

  override def request(stop: Bool, resume: Bool, step: Bool, clearSticky: Bool): Unit = {
    stopWire := stop
    resumeWire := resume
    stepWire := step
    clearStickyWire := clearSticky
  }
  override def configureHaltAfter(target: UInt, epoch: UInt, armed: Bool,
                                  invalidate: Bool): Unit = {
    haltAfterTargetWire := target
    haltAfterEpochWire := epoch
    haltAfterArmedWire := armed
    haltAfterInvalidateWire := invalidate
  }
  override def configureExceptionMask(mask: Bits): Unit = {
    haltExceptionMaskWire := mask
  }
}

/** Minimal AXI4-Lite master stimulus for `DbgAxiLite`. Every method leaves the bus idle
  * with `bready`/`rready` high, so successive calls compose. */
object DbgAxiDriver {

  def idle(b: DbgAxiLite): Unit = {
    b.awvalid #= false; b.awaddr #= 0
    b.wvalid  #= false; b.wdata  #= 0; b.wstrb #= 0
    b.bready  #= true
    b.arvalid #= false; b.araddr #= 0
    b.rready  #= true
  }

  /** AW and W presented in the same cycle; returns BRESP. */
  def write(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long, strb: Int = 0xF): Int = {
    b.bready  #= true
    b.awaddr  #= addr; b.awvalid #= true
    b.wdata   #= data; b.wstrb   #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean && b.wready.toBoolean)
    b.awvalid #= false; b.wvalid #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** AW accepted, then `gap` idle cycles, then W. Proves the AW skid register really
    * exists (spec 3.1: "capture AW and W independently"). */
  def writeAwFirst(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long,
                   strb: Int, gap: Int): Int = {
    b.bready  #= true
    b.awaddr  #= addr; b.awvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean)
    b.awvalid #= false
    cd.waitSampling(gap)
    b.wdata   #= data; b.wstrb #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.wready.toBoolean)
    b.wvalid  #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** W accepted, then `gap` idle cycles, then AW. */
  def writeWFirst(b: DbgAxiLite, cd: ClockDomain, addr: Long, data: Long,
                  strb: Int, gap: Int): Int = {
    b.bready  #= true
    b.wdata   #= data; b.wstrb #= strb; b.wvalid #= true
    cd.waitSamplingWhere(b.wready.toBoolean)
    b.wvalid  #= false
    cd.waitSampling(gap)
    b.awaddr  #= addr; b.awvalid #= true
    cd.waitSamplingWhere(b.awready.toBoolean)
    b.awvalid #= false
    cd.waitSamplingWhere(b.bvalid.toBoolean)
    val resp = b.bresp.toInt
    cd.waitSampling()
    resp
  }

  /** One read; returns RDATA as an unsigned 32-bit value in a Long. */
  def read(b: DbgAxiLite, cd: ClockDomain, addr: Long): Long = {
    b.rready  #= true
    b.araddr  #= addr; b.arvalid #= true
    cd.waitSamplingWhere(b.arready.toBoolean)
    b.arvalid #= false
    cd.waitSamplingWhere(b.rvalid.toBoolean)
    val data = b.rdata.toLong & 0xFFFFFFFFL
    cd.waitSampling()
    data
  }
}
