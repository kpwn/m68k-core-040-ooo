package m68k040.debug

// A7-ODD halt lane (2026-09-09): the hardware counterpart of the CPU040_A7_TRIPWIRE
// sim tripwire. A 68k stack pointer must never be odd, yet on the p164..p166 boards
// every mystery crash was downstream of a supervisor A7 that had gone odd somewhere
// inside a 156k-instruction window no halt-after bisection could pin (instruction
// counts are not a stable coordinate across boots). The lane halts the core once the
// COMMITTED A7 has stayed odd for a programmed number of retired macros and reports
// the PCs at the EVEN->ODD edge, so one boot names the instruction.
//
// The DUT is the real RobPlugin (which owns the ExceptionUnit and therefore the
// committed A7 = Mux(S, Mux(M, MSP, ISP), USP)) behind the real DebugCtrlPlugin at
// stage 5, driven through the dbg_axi window exactly as the board's JTAG REPL does.

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, Size}
import m68k040.mmu.MmuControlPlugin
import m68k040.rename.RenamedUop
import m68k040.rob.{DebugHaltReasonCode, DebugHaltState, RenameCommitSinkPlugin,
  RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class A7OddHaltLaneSpec extends AnyFunSuite {
  class Dut(pcRangeEnable: Boolean = true) extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin(pcRangeEnable = pcRangeEnable)
    val mmu = new MmuControlPlugin
    val commit = new RenameCommitSinkPlugin
    val intRf = new DebugIntRfStubPlugin
    val nzvcRf = new DebugNzvcRfStubPlugin
    val xRf = new DebugXRfStubPlugin
    val maps = new DebugCommittedMapStubPlugin
    val memory = new DebugMemoryStubPlugin
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = 5, pcRangeEnable = pcRangeEnable)
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), mmu, rsrc, alloc,
      rob, commit, intRf, nzvcRf, xRf, maps, memory, dbg)) }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  private def pokeRu(u: RenamedUop, pc: Long): Unit = {
    u.valid #= true
    u.pc #= pc
    u.lenWords #= 1
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
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
    u.faulted #= false
    u.faultVector #= 0
    u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.isCondTrap #= false
    u.sswInstr #= false
    u.firstOfInstr #= true
    u.lastOfInstr #= true
  }

  private def init(dut: Dut, cd: ClockDomain): Unit = {
    DbgAxiDriver.idle(dut.axi)
    dut.dbg.logic.initDoneSeen #= false
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    pokeRu(dut.rsrc.logic.src.payload(0), 0)
    pokeRu(dut.rsrc.logic.src.payload(1), 0)
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.flush.valid #= false
    cd.waitSampling(20)
    assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.RUNNING,
      "DUT did not leave reset in RUNNING")
    // Supervisor, M=0: the committed A7 is the ISP.
    dut.rob.logic.exc.ss.srSys #= 0x20
    dut.rob.logic.exc.ss.isp #= 0x003ff1b4L
    cd.waitSampling(2)
  }

  /** Allocate one macro at `pc`, complete it, and wait for it to retire. */
  private def retireOne(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    val before = dut.rob.logic.debugMacroCountReg.toBigInt
    pokeRu(dut.rsrc.logic.src.payload(0), pc)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= false
    val id = dut.rob.logic.tail.toInt
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= id
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= false
    var n = 0
    while (dut.rob.logic.debugMacroCountReg.toBigInt == before) {
      assert(n < 200, s"macro at 0x${pc.toHexString} never retired"); n += 1; cd.waitSampling()
    }
    cd.waitSampling()
  }

  private def rd(dut: Dut, cd: ClockDomain, off: Int): Long =
    DbgAxiDriver.read(dut.axi, cd, off.toLong)
  private def wr(dut: Dut, cd: ClockDomain, off: Int, v: Long): Unit =
    DbgAxiDriver.write(dut.axi, cd, off.toLong, v)
  private def halted(dut: Dut, cd: ClockDomain): Boolean =
    (rd(dut, cd, DebugRegMap.OFF_STATUS) & 1L) != 0
  private def waitHalt(dut: Dut, cd: ClockDomain, expect: Boolean, what: String): Unit = {
    var n = 0
    while (halted(dut, cd) != expect) {
      assert(n < 60, s"$what: STATUS never became halted=$expect " +
        s"(state=${dut.rob.logic.debugHaltState.toEnum} run=${dut.rob.logic.a7OddLane.run.toInt} " +
        s"req=${dut.rob.logic.a7OddLane.req.toBoolean})")
      n += 1
    }
  }

  test("threshold 0: halts on the EVEN->ODD edge with reason A7_ODD, re-arms only after A7 is even, disables cleanly") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut, cd)
      wr(dut, cd, DebugRegMap.OFF_A7ODD_CTL, 1L)          // enable, threshold 0
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_CTL) == 1L, "CTL did not read back")
      cd.waitSampling(10)
      assert(!halted(dut, cd), "halted with an EVEN A7")

      dut.rob.logic.exc.ss.isp #= 0x003ff1b5L             // ODD
      waitHalt(dut, cd, expect = true, "first odd episode")
      assert(rd(dut, cd, DebugRegMap.OFF_HALT_REASON) == DebugHaltReasonCode.A7_ODD,
        f"reason=${rd(dut, cd, DebugRegMap.OFF_HALT_REASON)} (expected A7_ODD=7)")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_VALUE) == 0x003ff1b5L, "captured A7 value")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_COUNT) == 1L, "episode count")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_PC0) == 0L, "no macro retired in the edge cycle")

      // Resume with A7 STILL odd: the lane must not re-trip until A7 has been even.
      wr(dut, cd, DebugRegMap.OFF_CONTROL, 0L)
      waitHalt(dut, cd, expect = false, "resume")
      cd.waitSampling(40)
      assert(!halted(dut, cd), "re-tripped while A7 was continuously odd")

      dut.rob.logic.exc.ss.isp #= 0x003ff1b4L             // even again -> re-arm
      cd.waitSampling(5)
      dut.rob.logic.exc.ss.isp #= 0x003ff1b7L             // second odd episode
      waitHalt(dut, cd, expect = true, "second odd episode")
      assert(rd(dut, cd, DebugRegMap.OFF_HALT_REASON) == DebugHaltReasonCode.A7_ODD)
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_VALUE) == 0x003ff1b7L)
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_COUNT) == 2L)

      // Disable, resume, and a fresh odd episode must be ignored.
      wr(dut, cd, DebugRegMap.OFF_A7ODD_CTL, 0L)
      wr(dut, cd, DebugRegMap.OFF_CONTROL, 0L)
      waitHalt(dut, cd, expect = false, "resume after disable")
      dut.rob.logic.exc.ss.isp #= 0x003ff1b4L
      cd.waitSampling(5)
      dut.rob.logic.exc.ss.isp #= 0x003ff1b9L
      cd.waitSampling(60)
      assert(!halted(dut, cd), "halted while the lane was disabled")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_COUNT) == 2L, "episodes counted while disabled")
    }
  }

  test("reduced debug omits PC-range hardware, ignores range writes, and preserves manual and A7 halt") {
    M68kSim().compile(new Dut(pcRangeEnable = false)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut, cd)
      assert(dut.rob.logic.pcRangeLane == null, "range capture hardware was elaborated")
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_LO, 0)
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_HI, 0xffffffffL)
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_CTL, 1)
      for (off <- Seq(DebugRegMap.OFF_PCRANGE_CTL, DebugRegMap.OFF_PCRANGE_LO,
        DebugRegMap.OFF_PCRANGE_HI, DebugRegMap.OFF_PCRANGE_PC0,
        DebugRegMap.OFF_PCRANGE_PC1, DebugRegMap.OFF_PCRANGE_PC2, DebugRegMap.OFF_PCRANGE_COUNT)) {
        assert(rd(dut, cd, off) == 0, s"disabled range register $off did not read zero")
      }
      for (pc <- Seq(0L, 0x1000L, 0x00a54cfaL, 0xfffffffeL)) retireOne(dut, cd, pc)
      assert(!halted(dut, cd), "disabled range hardware stopped retirement")
      wr(dut, cd, DebugRegMap.OFF_CONTROL, 1)
      waitHalt(dut, cd, expect = true, "manual halt without range hardware")
      wr(dut, cd, DebugRegMap.OFF_CONTROL, 0)
      waitHalt(dut, cd, expect = false, "manual resume without range hardware")
      wr(dut, cd, DebugRegMap.OFF_A7ODD_CTL, 1)
      dut.rob.logic.exc.ss.isp #= 0x003ff1b5L
      waitHalt(dut, cd, expect = true, "A7 halt without range hardware")
      assert(rd(dut, cd, DebugRegMap.OFF_HALT_REASON) == DebugHaltReasonCode.A7_ODD)
    }
  }

  test("PC-RANGE lane: halts on the first retirement inside the window, captures it and the two PCs before it, and is inert outside the window or when disabled") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut, cd)
      // Window = "RAM above 8 MB", the boot defect's wild-jump target region.
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_LO, 0x00800000L)
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_HI, 0x03FFFFFFL)
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_CTL, 1L)
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_LO) == 0x00800000L, "LO readback")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_HI) == 0x03FFFFFFL, "HI readback")

      // Retirements BELOW the window must not trip it.
      retireOne(dut, cd, 0x00009008L)
      retireOne(dut, cd, 0x0000900cL)
      retireOne(dut, cd, 0x00009012L)
      assert(!halted(dut, cd), "halted on an out-of-window PC")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_COUNT) == 0L, "counted an out-of-window PC")

      // The wild jump: one retirement inside the window halts the core.
      retireOne(dut, cd, 0x00a54cfaL)
      waitHalt(dut, cd, expect = true, "in-window retirement")
      assert(rd(dut, cd, DebugRegMap.OFF_HALT_REASON) == DebugHaltReasonCode.A7_ODD,
        "the PC-range lane reports the shared lane reason code")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_PC0) == 0x00a54cfaL, "PC0 = the in-window PC")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_PC1) == 0x00009012L, "PC1 = the PC before it")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_PC2) == 0x0000900cL, "PC2 = the one before that")
      assert(rd(dut, cd, DebugRegMap.OFF_PCRANGE_COUNT) == 1L, "in-range count")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_COUNT) == 0L, "the A7 lane must not have fired")

      // Disable, resume: a further in-window retirement is ignored.
      wr(dut, cd, DebugRegMap.OFF_PCRANGE_CTL, 0L)
      wr(dut, cd, DebugRegMap.OFF_CONTROL, 0L)
      waitHalt(dut, cd, expect = false, "resume after disable")
      retireOne(dut, cd, 0x00b78570L)
      cd.waitSampling(40)
      assert(!halted(dut, cd), "halted while the PC-range lane was disabled")
    }
  }

  test("threshold 4: counts retired macros while odd and reports the PCs retired before the edge") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      init(dut, cd)
      wr(dut, cd, DebugRegMap.OFF_A7ODD_CTL, (4L << 16) | 1L)
      // Even A7: retirements must not count.
      for (i <- 0 until 3) retireOne(dut, cd, 0x1000L + 2 * i)   // 0x1000, 0x1002, 0x1004
      assert(!halted(dut, cd), "halted with an even A7")
      assert(dut.rob.logic.a7OddLane.run.toInt == 0, "run counted while even")

      dut.rob.logic.exc.ss.isp #= 0x003ff1b5L             // ODD edge (no macro retiring)
      cd.waitSampling(3)
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_PC1) == 0x1004L, "pc1 = newest PC before the edge")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_PC2) == 0x1002L, "pc2 = the one before it")
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_COUNT) == 1L)

      for (i <- 0 until 3) retireOne(dut, cd, 0x2000L + 2 * i)   // run = 3 < 4
      cd.waitSampling(5)
      assert(!halted(dut, cd), s"halted below threshold (run=${dut.rob.logic.a7OddLane.run.toInt})")
      retireOne(dut, cd, 0x2006L)                                  // run = 4 -> stop
      waitHalt(dut, cd, expect = true, "threshold reached")
      assert(rd(dut, cd, DebugRegMap.OFF_HALT_REASON) == DebugHaltReasonCode.A7_ODD)
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_VALUE) == 0x003ff1b5L)
      assert(rd(dut, cd, DebugRegMap.OFF_A7ODD_PC1) == 0x1004L, "edge capture survived the halt")
    }
  }
}
