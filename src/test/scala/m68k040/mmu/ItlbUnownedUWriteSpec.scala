package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** THE I-SIDE U WRITE HAS NO OWNING robId, AND MUST NOT BORROW ONE.
  *
  * An instruction fetch is translated BEFORE rename: the I-side translation request
  * carries {vpn, supervisor, write} and nothing else, so there is no robId in
  * existence when the walk runs. `itlb.umAccessRobId` was therefore hardwired to 0 at
  * FOUR wiring sites (FullCoreSynth, FuzzDut, CoreBenchHarness, ExecuteLockStepSpec),
  * which meant the fetch's U-descriptor write became architectural whenever whatever
  * instruction happened to hold robId 0 retired -- an arbitrary time, under an
  * unrelated identity, and never at all if robId 0 was squashed first.
  *
  * The repair is `UmWriteAlloc.preCommitted`: a producer with no owner allocates its
  * entry ALREADY COMMITTED. It is a repair and not a semantic change because U is
  * MONOTONIC and ADVISORY and the old code ALREADY PERFORMED THIS WRITE -- just
  * nondeterministically. Born-committed makes an existing write deterministic; it adds
  * no new architectural commitment.
  *
  * NEGATIVE CONTROL: this DUT never pulses a commit for ANY robId. On the unfixed RTL
  * the entry sits uncommitted forever and the descriptor byte in memory keeps U = 0.
  * After the fix it drains on its own and the byte reads back with U set. */
class ItlbUnownedUWriteSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val itlb = new ItlbPlugin()
    val probe = new ItlbProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  test("an I-side U write has no owner and drains with NO commit pulse ever issued",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0
      p.reqIn.write #= false; p.reqIn.supervisor #= true
      p.commitValid #= false; p.commitId #= 0
      p.flush #= false; p.pflusha #= false
      cd.waitSampling(4)

      val va   = 0x02003000L
      val vpn  = (va >> 12) & 0xfffffL
      pokeWord(mem, ROOT + ((vpn >> 13) & 0x7f) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ((vpn >> 6) & 0x7f) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      val leafAddr = PAGT + (vpn & 0x3f) * 4
      // resident (PDT=01) with U CLEAR, so the walk must queue a real U-set write.
      pokeWord(mem, leafAddr, 0x00033000L | 0x01L)
      assert(mem.peekByte(leafAddr + 3) == 0x01, "the descriptor starts with U clear")

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.urp #= ROOT
      cd.waitSampling(2)

      // One fetch translation. NOTHING ever commits: `commitValid` stays false for the
      // whole test, which is the honest model of a producer with no owning robId.
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      var g = 0
      while (!p.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      assert(p.walkDone.toBoolean, "the walk must complete")
      p.reqIn.valid #= false

      // Give the drain ample time. The write is a single-byte RMW through the same
      // path the D side uses, so it needs a read and a write to land.
      g = 0
      while (mem.peekByte(leafAddr + 3) != 0x09 && g < 400) { cd.waitSampling(); g += 1 }
      val b = mem.peekByte(leafAddr + 3)
      info(f"descriptor low byte after the walk, with NO commit pulse ever issued: 0x$b%02x")
      assert(b == 0x09,
        f"the unowned I-side U write must drain on its own: descriptor low byte is " +
        f"0x$b%02x, expected 0x09 (PDT=01 | U). On the unfixed RTL this entry is tagged " +
        f"robId 0 and waits forever for a commit that never comes.")

      // ...and it must not have set M: a fetch is a READ, and only the D side sets M.
      assert((b & 0x10) == 0, f"an I-side walk must never set M; byte 0x$b%02x")
    }
  }
}
