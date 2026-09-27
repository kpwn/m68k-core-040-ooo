package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** ITLB VICTIM BUFFER — directed tests, with the OFF arm as the fail-before control.
  *
  * The claim under test is narrow and mechanical: a translation this array EVICTS is
  * kept in a fully-associative buffer, and re-touching it costs a one-cycle promote
  * instead of a three-level table search. So every test here is a PAIR — the same
  * stimulus against `victimEntries = 0` and `victimEntries = 32` — and what is asserted
  * is the DIFFERENCE in walk launches. A single-arm test could not distinguish "the
  * buffer works" from "the stimulus never evicted anything", which is exactly the
  * live-but-unexercised-knob failure this project has already been bitten by; so the
  * OFF arm must show the walks, and `vic.promote` must actually pulse in the ON arm.
  *
  * Geometry the stimulus is built against (`Tlb`, 4 KB pages so key == vpn):
  *   bank = key[0], set = key[2:1]  =>  row = key[2:0], 4 ways, round-robin victim.
  * Five VPNs sharing key[2:0] therefore land in ONE row and the fifth evicts the first.
  */
class ItlbVictimSpec extends AnyFunSuite {

  class Dut(victimEntries: Int) extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ctrl  = new MmuControlPlugin()
    val itlb  = new ItlbPlugin(victimEntries = victimEntries)
    val probe = new ItlbProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt

  /** All five VAs share one root slot and one pointer slot (they differ only in
    * vpn[5:3], which is the PAGE index) so they must share ONE page table -- giving each
    * its own would have every pointer write clobber the last, which is how the first cut
    * of this test read uninitialised memory and returned a random PPN. */
  private def buildTable(mem: DcacheClientMemAgent, va: Long, ppn: Long): Unit = {
    pokeWord(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWord(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    pokeWord(mem, PAGT + pageIdx(va) * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }

  /** Five VPNs that all index row 0 (key[2:0] == 0), plus their expected PPNs. */
  private val vpns = (0 until 5).map(i => 0x400L + i * 8)
  private val ppns = (0 until 5).map(i => 0x5000L + i)

  private def lookupUntilReady(dut: Dut, cd: ClockDomain, vpn: Long): (Long, Boolean) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= false
    dut.probe.logic.reqIn.supervisor #= true
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
    sleep(1)
    val r = (dut.probe.logic.rspOut.ppn.toLong, dut.probe.logic.rspOut.fault.toBoolean)
    dut.probe.logic.reqIn.valid #= false
    cd.waitSampling(2)
    r
  }

  /** Result of one run: walks launched during the re-touch phase, promotes seen, and
    * the PPN the re-touch resolved to (the correctness half — a promote that returns
    * the wrong PPN is worse than a walk). */
  private case class Run(retouchWalks: Int, promotes: Int, retouchPpn: Long, fault: Boolean,
                         primeWalks: Int)

  private def runScenario(victimEntries: Int, pflushaBeforeRetouch: Boolean): Run = {
    var out: Run = null
    SimConfig.withVerilator.compile(new Dut(victimEntries)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= true
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false
      dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      for (i <- vpns.indices) buildTable(mem, vpns(i) << 12, ppns(i))
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)

      var walkStarts = 0
      var promotes   = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.probe.logic.walkStart.toBoolean) walkStarts += 1
          if (victimEntries > 0 && dut.itlb.logic.vic.promote.toBoolean) promotes += 1
        }
      }

      // ── prime: five VPNs into a four-way row. The fifth evicts the first. ────────
      for (i <- vpns.indices) {
        val (ppn, fault) = lookupUntilReady(dut, cd, vpns(i))
        assert(!fault, s"prime lookup $i faulted")
        assert(ppn == ppns(i), f"prime $i: ppn 0x$ppn%x != 0x${ppns(i)}%x")
      }
      val primeWalks = walkStarts
      assert(primeWalks == 5, s"prime must launch exactly 5 walks, got $primeWalks")

      if (pflushaBeforeRetouch) {
        dut.probe.logic.pflusha #= true
        cd.waitSampling()
        dut.probe.logic.pflusha #= false
        cd.waitSampling(4)
      }

      // ── re-touch the evicted VPN. With no buffer this is a full walk; with the
      //    buffer it is a promote and no walk at all.
      walkStarts = 0
      promotes = 0
      val (rPpn, rFault) = lookupUntilReady(dut, cd, vpns(0))
      out = Run(walkStarts, promotes, rPpn, rFault, primeWalks)
    }
    out
  }

  test("ITLB victim buffer: a re-touched evicted page is promoted, not re-walked", VerilatorTest) {
    val off = runScenario(victimEntries = 0, pflushaBeforeRetouch = false)
    val on  = runScenario(victimEntries = 32, pflushaBeforeRetouch = false)

    // FAIL-BEFORE: the control arm must really pay for a walk, or the ON arm's zero
    // proves nothing about the buffer.
    assert(off.retouchWalks == 1,
      s"control (victimEntries=0) must re-walk the evicted page, got ${off.retouchWalks} walks")
    assert(off.promotes == 0, "control arm cannot promote -- it has no buffer")

    // THE CLAIM.
    assert(on.retouchWalks == 0,
      s"victim buffer must answer the re-touch with no table search, got ${on.retouchWalks} walks")
    assert(on.promotes == 1, s"expected exactly one promote, got ${on.promotes}")

    // CORRECTNESS: the promote must hand back the SAME translation the walk did.
    assert(!on.fault && !off.fault, "neither arm may fault on a resident page")
    assert(on.retouchPpn == ppns(0), f"promoted ppn 0x${on.retouchPpn}%x != 0x${ppns(0)}%x")
    assert(on.retouchPpn == off.retouchPpn,
      f"promote returned 0x${on.retouchPpn}%x, the walk returned 0x${off.retouchPpn}%x")
  }

  test("ITLB victim buffer: PFLUSHA clears it -- a promote must not survive a flush", VerilatorTest) {
    // The buffer is a third cache of the same page-table descriptors (array, sticky
    // walk-result latch, buffer). PFLUSHA clears the first two; if it did not clear the
    // third, a post-PFLUSHA fetch would be answered out of the PRE-flush address map
    // with no fault and a one-hot hit -- the exact shape `walkFlushPoison` exists to
    // prevent, reopened through a new door.
    val flushed = runScenario(victimEntries = 32, pflushaBeforeRetouch = true)
    assert(flushed.promotes == 0,
      s"PFLUSHA must invalidate the victim buffer, but ${flushed.promotes} promote(s) fired")
    assert(flushed.retouchWalks == 1,
      s"after PFLUSHA the re-touch must re-walk, got ${flushed.retouchWalks} walks")
    assert(!flushed.fault && flushed.retouchPpn == ppns(0),
      f"post-flush re-walk must still resolve correctly, got 0x${flushed.retouchPpn}%x")
  }
}
