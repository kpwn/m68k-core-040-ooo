package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.ls.BehavioralMemAgent
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
    override def supervisor: Bool = supervisorWire

    during setup {
      supervisorWire = Bool()
    }

    val logic = during build new Area {
      val supervisorIn = in Bool()
      supervisorWire := supervisorIn
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
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, priv, itlb, ic, probe)) }
    def walkerAxi = itlb.walkerAxi
  }

  private val Root      = 0x00010000L
  private val PtrTable  = 0x00011000L
  private val PageTable = 0x00012000L
  private val VirtPage  = 0x00402000L
  private val PhysPage  = 0x00800000L

  private def pokeWordBe(mem: BehavioralMemAgent, addr: Long, word: Long): Unit =
    for (i <- 0 until 4)
      mem.pokeByte(addr + i, ((word >> (8 * (3 - i))) & 0xff).toInt)

  private def installSupervisorMapping(mem: BehavioralMemAgent): Unit = {
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
      val walkerMem = new BehavioralMemAgent(dut.walkerAxi, cd)
      installSupervisorMapping(walkerMem)
      IcacheSim.attachMemory(dut.ic.logic.axi, cd, PhysPage, 0x1000)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.ic.logic.invalidateAll #= false
      dut.priv.logic.supervisorIn #= true
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      var walkArCount = 0
      cd.onSamplings {
        if (dut.ic.logic.axi.ar.valid.toBoolean && dut.ic.logic.axi.ar.ready.toBoolean)
          arTrace += ((dut.ic.logic.axi.ar.payload.id.toInt,
            dut.ic.logic.axi.ar.payload.addr.toLong))
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean)
          walkArCount += 1
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
      assert(walkArCount == 3,
        s"same-page speculation triggered an extra ITLB walk: $walkArCount descriptor reads")

      val demandBefore = arTrace.count(_._1 == AxiIds.I_DEMAND)
      assert(fetchData(dut, cd, VirtPage + 0x40L) == IcacheSim.window64(PhysPage + 0x40L))
      assert(arTrace.count(_._1 == AxiIds.I_DEMAND) == demandBefore,
        s"translated silent line failed to hit: $arTrace")
    }
  }

  test("resident supervisor permission fault stays associated at two-cycle latency",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      val walkerMem = new BehavioralMemAgent(dut.walkerAxi, cd)
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
      var rspCount = 0
      var coldUnresolvedCycles = 0
      cd.onSamplings {
        cycle += 1
        if (dut.ic.logic.axi.ar.valid.toBoolean && dut.ic.logic.axi.ar.ready.toBoolean)
          iArCount += 1
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean)
          walkArCount += 1
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
      val rspBefore = rspCount
      assert(iArBefore == 1, s"warm setup expected one demand refill, got $iArBefore")
      assert(walkArBefore == 3, s"warm setup expected one three-level walk, got $walkArBefore")
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
        "resident permission fault escaped before the response register")
      cd.waitSampling()
      assert(dut.probe.logic.rspOut.valid.toBoolean,
        "resident permission fault missing at N+2")
      assert(cycle - acceptCycle == 2,
        s"resident permission fault latency=${cycle - acceptCycle}, expected 2")
      assert(dut.probe.logic.rspOut.payload.pc.toLong == VirtPage,
        f"permission fault PC=0x${dut.probe.logic.rspOut.payload.pc.toLong}%x expected 0x$VirtPage%x")
      assert(dut.probe.logic.rspOut.payload.fault.toBoolean,
        "user fetch of resident supervisor-only page must fault")
      assert(dut.probe.logic.rspOut.payload.atc.toBoolean,
        "resident permission fault must be identified as ATC/MMU, not bus")
      assert(iArCount == iArBefore,
        s"resident permission fault launched an I-cache refill: $iArBefore -> $iArCount")
      assert(walkArCount == walkArBefore,
        s"resident permission fault re-walked the ITLB: $walkArBefore -> $walkArCount")
      assert(rspCount == rspBefore + 1,
        s"resident permission command produced ${rspCount - rspBefore} responses")
      cd.waitSampling(4)
      assert(rspCount == rspBefore + 1, "late duplicate permission response")
    }
  }
}
