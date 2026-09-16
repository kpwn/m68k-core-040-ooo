package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.mmu.MmuControlPlugin
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** EVERY offset of the debug CSR read mux, read over real `dbg_axi`, against a DUT whose
  * every source has been seeded with a distinct value.
  *
  * WHY THIS EXISTS (2026-09-17). The read mux used to be one `switch(arAddr)` with ~100
  * arms feeding `rData` directly; it is now decoded into six per-address-REGION partial
  * registers that stage 2 ORs together (see `DebugCtrlPlugin`'s read mux). That is a
  * mechanical but hand-done re-partition of a hundred arms, and the failure mode of
  * getting it wrong -- one arm dropped into no region, so its register reads zero -- is
  * exactly the failure mode `OFF_CYCLE_LO` already suffered once before (it had no
  * `is()` arm at all and fell through to `rData := 0`, so a healthy 200 MHz core
  * reported a dead clock). Nothing caught that for months, because no test read the
  * whole map.
  *
  * This test reads the whole map. A dropped arm shows up as a zero where a distinct
  * seeded value is expected. The seeds are deliberately chosen so that no two sources
  * share a value and none of them is zero, so "it reads back the right number" cannot be
  * satisfied by accident.
  *
  * `expected` also serves as the executable statement of what each offset MEANS. */
class DebugCtrlReadMuxSpec extends AnyFunSuite {

  /** Stage 5, every service DebugCtrlPlugin can consume present: the real ROB (which is
    * `DebugCommitService` + `DebugSystemStateService` + `DebugHistoryService`), the
    * committed-map / integer-PRF / memory stubs, and the frontend breakpoint matcher. */
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin
    val commit = new RenameCommitSinkPlugin
    val mmu = new MmuControlPlugin
    val intRf = new DebugIntRfStubPlugin
    val nzvcRf = new DebugNzvcRfStubPlugin
    val xRf = new DebugXRfStubPlugin
    val maps = new DebugCommittedMapStubPlugin
    val memory = new DebugMemoryStubPlugin
    val frontend = new FrontendDebugMatchStubPlugin
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x5A5A1234L), porCycles = 4, stage = 5)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), mmu, rsrc, alloc, rob, commit,
        intRf, nzvcRf, xRf, maps, memory, frontend, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  private def u(v: Long): Long = v & 0xFFFFFFFFL

  test("every implemented CSR offset reads its own distinct seeded source") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val b = dut.axi
      DbgAxiDriver.idle(b)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false; dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.memory.logic.quiescedDrive #= true
      dut.memory.logic.doneDrive #= false
      dut.memory.logic.errorDrive #= false
      for (i <- 0 until dut.maps.logic.intMap.length) dut.maps.logic.intMap(i) #= 0
      for (i <- 0 until dut.intRf.logic.values.length) dut.intRf.logic.values(i) #= 0
      cd.waitSampling(20)

      // ── Seed the ROB-owned sources (DebugCommitService + DebugSystemStateService) ──
      dut.rob.logic.exc.ss.srSys #= 0x30           // S=1 M=1 -> A7 is MSP
      dut.rob.logic.committedCcr #= 0x15
      dut.rob.logic.exc.ss.vbr #= 0x0ABC0000L
      dut.rob.logic.exc.ss.usp #= 0x11110000L
      dut.rob.logic.exc.ss.isp #= 0x22220000L
      dut.rob.logic.exc.ss.msp #= 0x33330000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.sfc #= 5
      dut.rob.logic.exc.ss.dfc #= 6
      // The MMU-side live words come from MmuControlPlugin, not from the ROB's own
      // system state: OFF_LIVE_MMU_TC is SYNTHESISED as {16'0, mmuEnable, pageSize8K, 14'0}.
      dut.mmu.logic.mmuEnable #= true
      dut.mmu.logic.pageSize8K #= true
      dut.mmu.logic.itt0 #= 0x0AAA0001L
      dut.mmu.logic.itt1 #= 0x0AAA0002L
      dut.mmu.logic.dtt0 #= 0x0AAA0003L
      dut.mmu.logic.dtt1 #= 0x0AAA0004L
      dut.mmu.logic.urp #= 0x0BBB0001L
      dut.mmu.logic.srp #= 0x0BBB0002L
      dut.mmu.logic.mmusr #= 0x0CCC0001L
      dut.rob.logic.debugLivePcReg #= 0x40100000L
      dut.rob.logic.debugLastPcReg #= 0x40100004L
      dut.rob.logic.debugMacroCountReg #= BigInt("00000011FEDCBA98", 16)
      dut.rob.logic.debugHaltHitInstCountReg #= BigInt("0000002212345678", 16)
      dut.rob.logic.debugHaltHitPcReg #= 0x40200000L
      dut.rob.logic.debugHaltExceptionVectorReg #= 0x2E
      dut.rob.logic.debugHaltExceptionPcReg #= 0x40300000L
      dut.rob.logic.debugHaltExceptionFaultAddressReg #= 0x40400000L
      dut.rob.logic.exc.dblFaultPc #= 0x40500000L
      dut.rob.logic.exc.dblFaultVec #= 0x23
      dut.rob.logic.a7OddLane.pc0 #= 0x40600000L
      dut.rob.logic.a7OddLane.pc1 #= 0x40600004L
      dut.rob.logic.a7OddLane.pc2 #= 0x40600008L
      dut.rob.logic.a7OddLane.value #= 0x00FFFF01L
      dut.rob.logic.a7OddLane.episodes #= 0x1234
      dut.rob.logic.pcRangeLane.pc0 #= 0x40700000L
      dut.rob.logic.pcRangeLane.pc1 #= 0x40700004L
      dut.rob.logic.pcRangeLane.pc2 #= 0x40700008L
      dut.rob.logic.pcRangeLane.count #= 0x4321

      // Committed map is deliberately NON-identity, so a live register read that ignored
      // the map would return the wrong physical register's value.
      dut.maps.logic.intMap(0) #= 20   // D0
      dut.maps.logic.intMap(7) #= 27   // D7
      dut.maps.logic.intMap(8) #= 30   // A0
      dut.maps.logic.intMap(14) #= 36  // A6
      dut.maps.logic.intMap(15) #= 41  // A7
      dut.intRf.logic.values(20) #= 0xD0000020L
      dut.intRf.logic.values(27) #= 0xD7000027L
      dut.intRf.logic.values(30) #= 0xA0000030L
      dut.intRf.logic.values(36) #= 0xA6000036L
      dut.intRf.logic.values(41) #= 0xA7000041L
      cd.waitSampling(4)

      // ── Seed every host-writable CSR through the real AXI write path ──────────────
      def wr(off: Int, data: Long): Unit =
        assert(DbgAxiDriver.write(b, cd, off.toLong, data) == 0, f"write to 0x$off%05x")

      wr(DebugRegMap.OFF_HALT_AFTER_LO, 0x0BADF00DL)
      wr(DebugRegMap.OFF_HALT_AFTER_HI, 0x0000BEEFL)
      wr(DebugRegMap.OFF_HALT_CTL, 1)                  // arms halt-after
      wr(DebugRegMap.OFF_BREAK_PC0, 0x00BC0000L)
      wr(DebugRegMap.OFF_BREAK_PC1, 0x00BC0004L)
      wr(DebugRegMap.OFF_BREAK_PC2, 0x00BC0008L)
      wr(DebugRegMap.OFF_BREAK_PC3, 0x00BC000CL)
      wr(DebugRegMap.OFF_BREAK_PC_CTRL, 0x5)
      wr(DebugRegMap.OFF_BP_SKIP_ONCE, 0xA)
      for (i <- 0 until 8) wr(DebugRegMap.OFF_HALT_EXC_MASK0 + i * 4, u(0xE0000001L + i))
      wr(DebugRegMap.OFF_A7ODD_CTL, u(0xABCD0000L))    // bit 0 clear: arm nothing
      wr(DebugRegMap.OFF_PCRANGE_CTL, u(0xDCBA0000L))  // bit 0 clear
      wr(DebugRegMap.OFF_PCRANGE_LO, 0x00110000L)
      wr(DebugRegMap.OFF_PCRANGE_HI, 0x00220000L)
      wr(DebugRegMap.OFF_RAM_WINDOW_LG2, 27)
      wr(DebugRegMap.OFF_MON_SENSE, 0x2A)
      for (i <- 0 until 8) wr(DebugRegMap.OFF_ARCH_D0 + i * 4, u(0x5D000000L + i))
      for (i <- 0 until 8) wr(DebugRegMap.OFF_ARCH_A0 + i * 4, u(0x5A000000L + i))
      val archMisc = Seq(
        DebugRegMap.OFF_ARCH_USP -> 0x5F000010L, DebugRegMap.OFF_ARCH_SSP -> 0x5F000011L,
        DebugRegMap.OFF_ARCH_ISP -> 0x5F000012L, DebugRegMap.OFF_ARCH_SR -> 0x5F000013L,
        DebugRegMap.OFF_ARCH_VBR -> 0x5F000014L, DebugRegMap.OFF_ARCH_CACR -> 0x5F000015L,
        DebugRegMap.OFF_ARCH_TC -> 0x5F000016L, DebugRegMap.OFF_ARCH_ITT0 -> 0x5F000017L,
        DebugRegMap.OFF_ARCH_ITT1 -> 0x5F000018L, DebugRegMap.OFF_ARCH_DTT0 -> 0x5F000019L,
        DebugRegMap.OFF_ARCH_DTT1 -> 0x5F00001AL, DebugRegMap.OFF_ARCH_URP -> 0x5F00001BL,
        DebugRegMap.OFF_ARCH_SRP -> 0x5F00001CL, DebugRegMap.OFF_ARCH_PC -> 0x5F00001DL,
        DebugRegMap.OFF_ARCH_SFC -> 0x5F00001EL, DebugRegMap.OFF_ARCH_DFC -> 0x5F00001FL)
      archMisc.foreach { case (o, v) => wr(o, v) }
      cd.waitSampling(4)

      // ── Expected values, offset by offset ─────────────────────────────────────────
      val expected = scala.collection.mutable.LinkedHashMap[Int, Long]()
      def exp(off: Int, v: Long): Unit = expected(off) = u(v)

      exp(DebugRegMap.OFF_VERSION, DebugRegMap.VERSION_VALUE.toLong)
      exp(DebugRegMap.OFF_BUILD_ID, 0x5A5A1234L)
      exp(DebugRegMap.OFF_CAP_TRACE, 0x00200020L)
      exp(DebugRegMap.OFF_CAP_TRACE2, 0x00000020L)
      exp(DebugRegMap.OFF_PC, 0x40100000L)
      exp(DebugRegMap.OFF_LAST_PC, 0x40100004L)
      exp(DebugRegMap.OFF_LIVE_PC, 0x40100000L)
      exp(DebugRegMap.OFF_HALT_AFTER_LO, 0x0BADF00DL)
      exp(DebugRegMap.OFF_HALT_AFTER_HI, 0x0000BEEFL)
      exp(DebugRegMap.OFF_HALT_CTL, 1)
      exp(DebugRegMap.OFF_BREAK_PC0, 0x00BC0000L)
      exp(DebugRegMap.OFF_BREAK_PC1, 0x00BC0004L)
      exp(DebugRegMap.OFF_BREAK_PC2, 0x00BC0008L)
      exp(DebugRegMap.OFF_BREAK_PC3, 0x00BC000CL)
      exp(DebugRegMap.OFF_BREAK_PC_CTRL, 0x5)
      exp(DebugRegMap.OFF_BP_SKIP_ONCE, 0xA)
      for (i <- 0 until 8) exp(DebugRegMap.OFF_HALT_EXC_MASK0 + i * 4, 0xE0000001L + i)
      exp(DebugRegMap.OFF_HALT_HIT_PC, 0x40200000L)
      exp(DebugRegMap.OFF_HALT_HIT_INST_LO, 0x12345678L)
      exp(DebugRegMap.OFF_HALT_HIT_INST_HI, 0x00000022L)
      exp(DebugRegMap.OFF_EXC_VEC, 0x2E)
      exp(DebugRegMap.OFF_EXC_PC, 0x40300000L)
      exp(DebugRegMap.OFF_EXC_FAULT_ADDR, 0x40400000L)
      exp(DebugRegMap.OFF_DBL_FAULT_PC, 0x40500000L)
      exp(DebugRegMap.OFF_DBL_FAULT_VEC, 0x23)
      exp(DebugRegMap.OFF_A7ODD_CTL, 0xABCD0000L)
      exp(DebugRegMap.OFF_A7ODD_PC0, 0x40600000L)
      exp(DebugRegMap.OFF_A7ODD_PC1, 0x40600004L)
      exp(DebugRegMap.OFF_A7ODD_PC2, 0x40600008L)
      exp(DebugRegMap.OFF_A7ODD_VALUE, 0x00FFFF01L)
      exp(DebugRegMap.OFF_A7ODD_COUNT, 0x1234)
      exp(DebugRegMap.OFF_PCRANGE_CTL, 0xDCBA0000L)
      exp(DebugRegMap.OFF_PCRANGE_LO, 0x00110000L)
      exp(DebugRegMap.OFF_PCRANGE_HI, 0x00220000L)
      exp(DebugRegMap.OFF_PCRANGE_PC0, 0x40700000L)
      exp(DebugRegMap.OFF_PCRANGE_PC1, 0x40700004L)
      exp(DebugRegMap.OFF_PCRANGE_PC2, 0x40700008L)
      exp(DebugRegMap.OFF_PCRANGE_COUNT, 0x4321)
      exp(DebugRegMap.OFF_RAM_WINDOW_LG2, 27)
      exp(DebugRegMap.OFF_MON_SENSE, 0x2A)
      exp(DebugRegMap.OFF_INST_LO, 0xFEDCBA98L)
      exp(DebugRegMap.OFF_INST_HI, 0x00000011L)
      for (i <- 0 until 8) exp(DebugRegMap.OFF_ARCH_D0 + i * 4, 0x5D000000L + i)
      for (i <- 0 until 8) exp(DebugRegMap.OFF_ARCH_A0 + i * 4, 0x5A000000L + i)
      archMisc.foreach { case (o, v) => exp(o, v) }
      exp(DebugRegMap.OFF_LIVE_VBR, 0x0ABC0000L)
      exp(DebugRegMap.OFF_LIVE_USP, 0x11110000L)
      exp(DebugRegMap.OFF_LIVE_ISP, 0x22220000L)
      exp(DebugRegMap.OFF_LIVE_SSP, 0x33330000L)
      exp(DebugRegMap.OFF_LIVE_CACR, 0x80008000L)
      exp(DebugRegMap.OFF_LIVE_SFC, 5)
      exp(DebugRegMap.OFF_LIVE_DFC, 6)
      exp(DebugRegMap.OFF_LIVE_MMU_TC, 0x0000C000L)
      exp(DebugRegMap.OFF_LIVE_MMU_ITT0, 0x0AAA0001L)
      exp(DebugRegMap.OFF_LIVE_MMU_ITT1, 0x0AAA0002L)
      exp(DebugRegMap.OFF_LIVE_MMU_DTT0, 0x0AAA0003L)
      exp(DebugRegMap.OFF_LIVE_MMU_DTT1, 0x0AAA0004L)
      exp(DebugRegMap.OFF_LIVE_MMU_URP, 0x0BBB0001L)
      exp(DebugRegMap.OFF_LIVE_MMU_SRP, 0x0BBB0002L)
      exp(DebugRegMap.OFF_LIVE_MMUSR, 0x0CCC0001L)
      // Live integer registers: committed map is non-identity, so each of these proves
      // the RAT walk AND the physical-register read, not just an index.
      exp(DebugRegMap.OFF_LIVE_DREG0, 0xD0000020L)
      exp(DebugRegMap.OFF_LIVE_DREG7, 0xD7000027L)
      exp(DebugRegMap.OFF_LIVE_AREG0, 0xA0000030L)
      exp(DebugRegMap.OFF_LIVE_AREG6, 0xA6000036L)
      exp(DebugRegMap.OFF_LIVE_AREG7, 0xA7000041L)
      exp(DebugRegMap.OFF_LIVE_A7, 0xA7000041L)

      expected.foreach { case (off, want) =>
        val got = DbgAxiDriver.read(b, cd, off.toLong)
        assert(got == want,
          f"offset 0x$off%05x read 0x$got%08x, expected 0x$want%08x")
      }

      // ── Sources that are not constants: check the PROPERTY, not a fixed value ─────
      // OFF_CYCLE_LO/HI is the only measurement of the core clock this project has, and
      // it has been silently broken once before. It must ADVANCE, and it must advance by
      // exactly the number of clocks that passed.
      val c0 = DbgAxiDriver.read(b, cd, DebugRegMap.OFF_CYCLE_LO.toLong)
      cd.waitSampling(50)
      val c1 = DbgAxiDriver.read(b, cd, DebugRegMap.OFF_CYCLE_LO.toLong)
      assert(c1 > c0 && (c1 - c0) >= 50,
        s"OFF_CYCLE_LO must advance one per core clock ($c0 -> $c1)")
      assert(DbgAxiDriver.read(b, cd, DebugRegMap.OFF_CYCLE_HI.toLong) == 0,
        "OFF_CYCLE_HI must still be the HIGH word, not a second copy of LO")

      // STATUS/HALT_REASON/HALT_KIND/ARCH_STATUS/{D,I}CACHE_OP/EXC_COUNT and the three
      // ring heads are live ROB/FSM state; they are covered behaviourally by
      // DebugCtrlCsrSpec, DebugCtrlRobIntegrationSpec and DebugHistorySpec. Here they
      // only have to be READABLE -- i.e. their arm still exists and the transaction
      // completes -- which the loop below asserts by completing without a timeout.
      Seq(DebugRegMap.OFF_STATUS, DebugRegMap.OFF_CONTROL, DebugRegMap.OFF_HALT_REASON,
        DebugRegMap.OFF_HALT_KIND, DebugRegMap.OFF_EXC_COUNT, DebugRegMap.OFF_FEATURES,
        DebugRegMap.OFF_DBG_RESET_CTL, DebugRegMap.OFF_ARCH_APPLY,
        DebugRegMap.OFF_ARCH_STATUS, DebugRegMap.OFF_DCACHE_OP, DebugRegMap.OFF_ICACHE_OP,
        DebugRegMap.OFF_STALL_DC, DebugRegMap.OFF_STALL_GRANT, DebugRegMap.OFF_STALL_EXC,
        DebugRegMap.OFF_STALL_WALK, DebugRegMap.OFF_PC_TRACE_HEAD,
        DebugRegMap.OFF_EXC_RING_HEAD, DebugRegMap.OFF_BRANCH_RING_HEAD)
        .foreach(o => DbgAxiDriver.read(b, cd, o.toLong))

      // ── RESERVED offsets must still read ZERO (spec 3.2) ──────────────────────────
      // The region-OR reproduces the old switch's "no arm -> zero" default only if no
      // region claims an address it does not implement. These are the reserved ones.
      Seq(DebugRegMap.OFF_REDIRECT_PC, DebugRegMap.OFF_REDIRECT_TRIGGER,
        DebugRegMap.OFF_RESET_CAUSE, DebugRegMap.OFF_HALT_EXC_VEC,
        DebugRegMap.OFF_PC_MISALIGNED_PC, DebugRegMap.OFF_WP0_ADDR,
        DebugRegMap.OFF_WP_HIT, DebugRegMap.OFF_AT0_CTRL, DebugRegMap.OFF_AT_HIT,
        DebugRegMap.OFF_DCACHE_PROBE_SEL, DebugRegMap.OFF_DCACHE_PROBE_DATA,
        DebugRegMap.OFF_MISPRED_COUNT, DebugRegMap.OFF_FLUSH_COUNT,
        DebugRegMap.OFF_WEDGE0, DebugRegMap.OFF_FAULT_SNAP_VALID,
        DebugRegMap.OFF_RTS_SNAP_VALID)
        .foreach { o =>
          val got = DbgAxiDriver.read(b, cd, o.toLong)
          assert(got == 0L, f"reserved offset 0x$o%05x must read zero, read 0x$got%08x")
        }
    }
  }
}
