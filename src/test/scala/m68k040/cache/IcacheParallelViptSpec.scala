package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.sim.DcacheClientMemAgent
import m68k040.mmu.{ItlbPlugin, MmuControlPlugin}
import m68k040.services.PrivilegeService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Resident-permission association proof for the registered parallel-VIPT L1I.
  *
  * A supervisor-only page is first walked and demand-filled in supervisor mode. The
  * test then changes only the access class to user and re-offers the same resident
  * virtual line. The ITLB must report its hit-class permission fault against that exact
  * command, while the I-cache returns the fault at resident latency without consulting
  * either AXI master or accidentally serving the resident bytes as a clean hit. */
class IcacheParallelViptSpec extends AnyFunSuite {

  class TestPrivilegePlugin extends FiberPlugin with PrivilegeService {
    private var supervisorWire: Bool = null
    // SFC / DFC (2026-09-09): the service acquired the MOVES function-code pair.
    // This DUT is I-side only and there is no such thing as an instruction fetch in
    // an alternate address space, so both simply MIRROR the privilege level in the
    // 68040's own FC encoding (5 = supervisor data, 1 = user data; FC[2] is the
    // address-space selector). Deriving them from `supervisorIn` rather than tying
    // them to a constant keeps this stub HONEST if a future consumer reads them:
    // a constant would silently disagree with the DUT's own privilege input.
    private var sourceFcWire: UInt = null
    private var destFcWire: UInt = null
    override def supervisor: Bool = supervisorWire
    override def sourceFc: UInt = sourceFcWire
    override def destFc: UInt = destFcWire

    during setup {
      supervisorWire = Bool()
      sourceFcWire = UInt(3 bits)
      destFcWire = UInt(3 bits)
    }

    val logic = during build new Area {
      val supervisorIn = in Bool()
      supervisorWire := supervisorIn
      val fc = Mux(supervisorIn, U(5, 3 bits), U(1, 3 bits))
      sourceFcWire := fc
      destFcWire   := fc
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ctrl  = new MmuControlPlugin
    val priv  = new TestPrivilegePlugin
    val itlb  = new ItlbPlugin()
    val ic    = new IcachePlugin
    val probe = new FetchProbePlugin
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, priv, itlb, ic, probe, walkPort)) }
    // No DcachePlugin in this DUT: expose the ITLB walker's DcacheService client port
    // pair as DUT IO and let `DcacheClientMemAgent` answer it. 68040 table searches are
    // DATA accesses even for an instruction translation, which is why the I-side walker
    // is a D-cache client and not an I-cache one.
  }

  private val Root      = 0x00010000L
  private val PtrTable  = 0x00011000L
  private val PageTable = 0x00012000L
  private val VirtPage  = 0x00402000L
  private val PhysPage  = 0x00800000L

  /** The descriptor-port transactions ONE cold table search for `VirtPage` produces.
    *
    * NOT three. `walkLoadCmd` is the ITLB walker's D-cache CLIENT port, and it carries
    * two different things: the three descriptor READS of the table search, and then the
    * read half of the U-bit READ-MODIFY-WRITE that the deferred U/M write queue drains
    * afterwards. The leaf descriptors these tests plant have U clear, so a clean walk
    * legitimately queues a U write, and since `053e93f7` the I-side entry is born
    * committed (an instruction fetch is translated before rename, so it owns no robId)
    * and therefore drains unaided -- in a DUT like this one, which has no ROB and never
    * pulses a commit, it previously waited forever and the 4th transaction never
    * appeared. `0x1200b` is `PageTable + pageIdx*4 + 3`: byte 3 of the leaf descriptor,
    * which is where U (bit 3) lives in a big-endian long.
    *
    * Spelling the sequence out, rather than bumping a `== 3` to a `== 4`, keeps the
    * U-drain read NAMED. A bumped constant would also have been satisfied by a second
    * root-descriptor read or a re-walk of a different VPN, which is the failure this
    * assertion exists to catch -- so the walk count itself is asserted separately, off
    * `walker.io.start`, which is the property these tests actually mean. */
  private val ColdWalkPortTxns: Seq[Long] = {
    val rootIdx = ((VirtPage >> 25) & 0x7f).toInt
    val ptrIdx  = ((VirtPage >> 18) & 0x7f).toInt
    val pageIdx = ((VirtPage >> 12) & 0x3f).toInt
    val leaf    = PageTable + pageIdx * 4L
    Seq(Root + rootIdx * 4L, PtrTable + ptrIdx * 4L, leaf, leaf + 3L)
  }

  private def pokeWordBe(mem: m68k040.sim.DcacheClientMemAgent, addr: Long, word: Long): Unit =
    for (i <- 0 until 4)
      mem.pokeByte(addr + i, ((word >> (8 * (3 - i))) & 0xff).toInt)

  private def installSupervisorMapping(mem: m68k040.sim.DcacheClientMemAgent): Unit = {
    val rootIdx = ((VirtPage >> 25) & 0x7f).toInt
    val ptrIdx  = ((VirtPage >> 18) & 0x7f).toInt
    val pageIdx = ((VirtPage >> 12) & 0x3f).toInt
    pokeWordBe(mem, Root + rootIdx * 4L, (PtrTable & 0xfffffff0L) | 0x3L)
    pokeWordBe(mem, PtrTable + ptrIdx * 4L, (PageTable & 0xfffffff0L) | 0x3L)
    // Leaf PDT=resident plus S=1 (bit 7): supervisor-only, cacheable.
    pokeWordBe(mem, PageTable + pageIdx * 4L, (PhysPage & 0xfffff000L) | 0x81L)
  }

  private def fetchAndCheckClean(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.valid.toBoolean &&
      dut.probe.logic.cmdIn.ready.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    assert(!dut.probe.logic.rspOut.payload.fault.toBoolean,
      "supervisor warm fetch unexpectedly faulted")
  }

  private def fetchData(dut: Dut, cd: ClockDomain, pc: Long): BigInt = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.valid.toBoolean &&
      dut.probe.logic.cmdIn.ready.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    assert(!dut.probe.logic.rspOut.payload.fault.toBoolean)
    dut.probe.logic.rspOut.payload.data.toBigInt
  }

  test("nonidentity mapping seeds physical same-page silent lines without another walk",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val walkerMem = new m68k040.sim.DcacheClientMemAgent(dut.walkPort, cd)
      installSupervisorMapping(walkerMem)
      IcacheSim.attachMemory(dut.ic.logic.axi, cd, PhysPage, 0x1000)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.ic.logic.invalidateAll #= false
      dut.priv.logic.supervisorIn #= true
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      val walkPortTxns = scala.collection.mutable.ArrayBuffer[Long]()
      var walkStarts = 0
      cd.onSamplings {
        if (dut.ic.logic.axi.ar.valid.toBoolean && dut.ic.logic.axi.ar.ready.toBoolean)
          arTrace += ((dut.ic.logic.axi.ar.payload.id.toInt,
            dut.ic.logic.axi.ar.payload.addr.toLong))
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean)
          walkPortTxns += dut.walkPort.logic.cmd.payload.paddr.toLong
        if (dut.itlb.logic.walker.io.start.toBoolean) walkStarts += 1
      }

      cd.waitSampling(5)
      dut.ic.logic.prefetchEnable #= true
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      dut.ctrl.logic.mmuEnable #= true
      cd.waitSampling(2)

      assert(fetchData(dut, cd, VirtPage) == IcacheSim.window64(PhysPage),
        "nonidentity demand data did not use the translated physical line")
      cd.waitSampling(60)
      assert(arTrace.contains((AxiIds.I_DEMAND, PhysPage)), s"missing physical demand AR: $arTrace")
      (AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).foreach { id =>
        val expected = PhysPage + id * 64L
        assert(arTrace.contains((id, expected)),
          f"silent ID$id did not inherit translated PPN: expected 0x$expected%x trace=$arTrace")
      }
      assert(walkStarts == 1,
        s"same-page speculation triggered an extra ITLB walk: $walkStarts walk launches " +
          s"(walk-port transactions: ${walkPortTxns.map(a => f"0x$a%x")})")
      assert(walkPortTxns.toSeq == ColdWalkPortTxns,
        s"walk-port transactions ${walkPortTxns.map(a => f"0x$a%x")} != the expected cold " +
          s"search + U-drain RMW read ${ColdWalkPortTxns.map(a => f"0x$a%x")}")

      val demandBefore = arTrace.count(_._1 == AxiIds.I_DEMAND)
      assert(fetchData(dut, cd, VirtPage + 0x40L) == IcacheSim.window64(PhysPage + 0x40L))
      assert(arTrace.count(_._1 == AxiIds.I_DEMAND) == demandBefore,
        s"translated silent line failed to hit: $arTrace")
    }
  }

  // RENAMED AND RE-POINTED 2026-09-09 (was "resident supervisor permission fault stays
  // associated at two-cycle latency"). The scenario it measured no longer exists, and
  // the reason is worth stating rather than quietly deleting a cycle count:
  //
  // The ATC tag now carries FC2 (`Tlb.tagSup`), because the 68040 has SEPARATE user and
  // supervisor root pointers and one logical address therefore translates to DIFFERENT
  // pages in the two spaces. The warm-up below fetches `VirtPage` in SUPERVISOR mode, so
  // the only resident entry is supervisor-tagged; the USER fetch that follows can no
  // longer be answered from it and must walk. It still FAULTS -- this DUT sets
  // URP = SRP, so the walk reaches the same descriptor and finds its S bit set -- but it
  // is no longer a resident hit, so "two-cycle latency" and "must not re-walk" are not
  // properties this access can have any more.
  //
  // WHY THE 2-CYCLE CASE IS UNREACHABLE RATHER THAN JUST MOVED: this core does not fill
  // the ATC on a FAULTING walk (`DtlbPlugin`/`ItlbPlugin` gate `fillValid` on
  // `!walker.io.rsp.fault`), so a faulting user access never leaves a user-tagged entry
  // behind for a second one to hit. A real 68040 DOES cache faulting translations, which
  // is what makes "resident permission fault, no re-walk" reachable on silicon. That
  // divergence is PRE-EXISTING and is recorded as a known deviation in
  // docs/KNOWN_DEVIATION_atc_no_fill_on_faulting_walk.md -- it is deliberately NOT
  // changed here.
  //
  // Everything else this test proved is kept and still asserted: the verdict is a
  // fault, it is attributed to the ATC rather than the bus, it carries the right PC, it
  // does NOT launch an I-cache refill, and it produces exactly one response with no late
  // duplicate. The walk is now asserted EXPLICITLY (it used to be asserted absent).
  test("a user fetch of a supervisor-only page faults via a walk, with no refill and exactly one response",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      val walkerMem = new m68k040.sim.DcacheClientMemAgent(dut.walkPort, cd)
      installSupervisorMapping(walkerMem)
      val words = Seq.tabulate(1024)(i => 0x7000 | (i & 7))
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, PhysPage, words)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.ic.logic.invalidateAll #= false
      dut.priv.logic.supervisorIn #= true

      var cycle = 0
      var iArCount = 0
      var walkArCount = 0
      var walkStarts = 0
      val walkPortTxns = scala.collection.mutable.ArrayBuffer[Long]()
      var rspCount = 0
      var coldUnresolvedCycles = 0
      cd.onSamplings {
        cycle += 1
        if (dut.ic.logic.axi.ar.valid.toBoolean && dut.ic.logic.axi.ar.ready.toBoolean)
          iArCount += 1
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) {
          walkArCount += 1
          walkPortTxns += dut.walkPort.logic.cmd.payload.paddr.toLong
        }
        if (dut.itlb.logic.walker.io.start.toBoolean) walkStarts += 1
        if (dut.probe.logic.rspOut.valid.toBoolean) rspCount += 1
        if (dut.probe.logic.cmdIn.valid.toBoolean && !dut.ic.logic.xlateReadyDbg.toBoolean) {
          coldUnresolvedCycles += 1
          assert(dut.ic.logic.ufaReadEn.toBoolean,
            "VIPT data-array read was gated by unresolved ITLB response")
        }
      }

      cd.waitSampling(5)
      // RegInit(True): apply the measurement control only after reset releases.
      dut.ic.logic.prefetchEnable #= false
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      dut.ctrl.logic.mmuEnable #= true
      cd.waitSampling(2)

      fetchAndCheckClean(dut, cd, VirtPage)
      cd.waitSampling(4)
      val iArBefore = iArCount
      val walkArBefore = walkArCount
      val walkStartsBefore = walkStarts
      val rspBefore = rspCount
      assert(iArBefore == 1, s"warm setup expected one demand refill, got $iArBefore")
      assert(walkStartsBefore == 1,
        s"warm setup expected one three-level walk, got $walkStartsBefore walk launches " +
          s"(walk-port transactions: ${walkPortTxns.map(a => f"0x$a%x")})")
      assert(walkPortTxns.toSeq == ColdWalkPortTxns,
        s"walk-port transactions ${walkPortTxns.map(a => f"0x$a%x")} != the expected cold " +
          s"search + U-drain RMW read ${ColdWalkPortTxns.map(a => f"0x$a%x")}")
      assert(coldUnresolvedCycles > 0,
        "setup never observed a live ITLB miss, so the parallel VIPT check was vacuous")

      dut.priv.logic.supervisorIn #= false
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= VirtPage
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.valid.toBoolean &&
        dut.probe.logic.cmdIn.ready.toBoolean)
      val acceptCycle = cycle
      dut.probe.logic.cmdIn.valid #= false

      cd.waitSampling()
      assert(!dut.probe.logic.rspOut.valid.toBoolean,
        "the permission fault escaped ahead of the response register")
      // The user-space fetch MISSES (its address space has no resident entry), so its
      // verdict arrives only after a table search rather than at the registered
      // two-cycle resident latency. Wait for it instead of sampling a fixed cycle.
      var permWait = 0
      while (!dut.probe.logic.rspOut.valid.toBoolean && permWait < 200) {
        cd.waitSampling(); permWait += 1
      }
      assert(dut.probe.logic.rspOut.valid.toBoolean,
        s"the user fetch never produced a verdict at all (waited $permWait cycles after " +
          s"accept at cycle $acceptCycle)")
      assert(dut.probe.logic.rspOut.payload.pc.toLong == VirtPage,
        f"permission fault PC=0x${dut.probe.logic.rspOut.payload.pc.toLong}%x expected 0x$VirtPage%x")
      assert(dut.probe.logic.rspOut.payload.fault.toBoolean,
        "a USER fetch of a supervisor-only page must fault")
      assert(dut.probe.logic.rspOut.payload.atc.toBoolean,
        "the permission fault must be identified as ATC/MMU, not bus")
      assert(iArCount == iArBefore,
        s"the faulting fetch launched an I-cache refill: $iArBefore -> $iArCount")
      assert(walkStarts > walkStartsBefore,
        s"the USER fetch MUST have walked -- its address space has no resident entry, " +
          s"and answering it from the SUPERVISOR-tagged one would be reading the wrong " +
          s"tree (walk launches $walkStartsBefore -> $walkStarts, walk-port transactions " +
          s"$walkArBefore -> $walkArCount)")
      assert(rspCount == rspBefore + 1,
        s"the permission command produced ${rspCount - rspBefore} responses")
      cd.waitSampling(4)
      assert(rspCount == rspBefore + 1, "late duplicate permission response")
    }
  }
}
