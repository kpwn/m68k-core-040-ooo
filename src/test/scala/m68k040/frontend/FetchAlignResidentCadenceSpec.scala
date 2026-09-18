package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.sim.DcacheClientMemAgent
import m68k040.mmu.{ItlbPlugin, MmuControlPlugin}
import m68k040.services.FetchService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** End-to-end cadence proof for the real ITLB-hit -> resident L1I -> FetchAlign path.
  *
  * The cache is warmed through real demand misses and the ITLB through a real three-level
  * walk. The measured arms then require useful two-wide decode packets on consecutive
  * cycles. This catches a periodic fetch-ring bubble even if ic.cmd itself happens to
  * fire, and exact unique PCs/opwords catch stale aliasing, duplication, or loss.
  */
class FetchAlignResidentCadenceSpec extends AnyFunSuite {

  /** Read-only service observer. Keeping this as a sibling plugin preserves the normal
    * plugin boundary and prevents Verilator pruning the internal cmd/rsp wires that the
    * cadence assertions need to sample. */
  class FetchObservePlugin extends FiberPlugin {
    val logic = during build new Area {
      val fetch = host[FetchService]
      val cmdValid = out(Bool())
      val cmdReady = out(Bool())
      val cmdPc = out(UInt(32 bits))
      val rspValid = out(Bool())
      val rspPc = out(UInt(32 bits))
      cmdValid := fetch.cmd.valid
      cmdReady := fetch.cmd.ready
      cmdPc := fetch.cmd.payload.pc
      rspValid := fetch.rsp.valid
      rspPc := fetch.rsp.payload.pc
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ctrl  = new MmuControlPlugin
    val itlb  = new ItlbPlugin()
    val ic    = new IcachePlugin
    val fa    = new FetchAlignPlugin
    val probe = new DecodeFeedProbePlugin
    val obs   = new FetchObservePlugin
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), ctrl, itlb, ic, fa, probe, obs, walkPort)) }
    // No DcachePlugin in this DUT: expose the ITLB walker's DcacheService client port
    // pair as DUT IO and let `DcacheClientMemAgent` answer it. 68040 table searches are
    // DATA accesses even for an instruction translation, which is why the I-side walker
    // is a D-cache client and not an I-cache one.
  }

  private case class FetchEvent(cycle: Int, pc: Long)
  private case class Group(cycle: Int,
                           pc0: Long, op0: Int, ext0: Int,
                           slot1: Boolean,
                           pc1: Long, op1: Int, ext1: Int)

  private val Root = 0x00010000L
  private val PtrTable = 0x00011000L
  private val PageTable = 0x00012000L
  private val VirtPage = 0x00402000L
  private val PhysPage = 0x00800000L

  /** The descriptor-port transactions ONE cold table search for `VirtPage` produces.
    *
    * NOT three. `walkLoadCmd` is the ITLB walker's D-cache CLIENT port, and it carries
    * two different things: the three descriptor READS of the table search, and then the
    * read half of the U-bit READ-MODIFY-WRITE that the deferred U/M write queue drains
    * afterwards. The leaf descriptor planted below has U clear, so a clean walk
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
    * `walker.io.start`, which is the property this test actually means. */
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

  private def installMapping(mem: m68k040.sim.DcacheClientMemAgent): Unit = {
    val rootIdx = ((VirtPage >> 25) & 0x7f).toInt
    val ptrIdx  = ((VirtPage >> 18) & 0x7f).toInt
    val pageIdx = ((VirtPage >> 12) & 0x3f).toInt
    pokeWordBe(mem, Root + rootIdx * 4L, (PtrTable & 0xfffffff0L) | 0x3L)
    pokeWordBe(mem, PtrTable + ptrIdx * 4L, (PageTable & 0xfffffff0L) | 0x3L)
    pokeWordBe(mem, PageTable + pageIdx * 4L, (PhysPage & 0xfffff000L) | 0x1L)
  }

  private def opcodeAt(pc: Long): Int =
    0x0a40 // EORI.W #imm,D0: framed simple, two words

  private def extensionAt(pc: Long): Int =
    0x1000 | (((pc - VirtPage) / 4).toInt & 0x0fff)

  private def pulseRedirect(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.fa.logic.redirect.valid #= true
    dut.fa.logic.redirect.payload #= pc
    cd.waitSampling()
    sleep(1) // observe the post-edge IBuf flush before a warm-phase completion check
    dut.fa.logic.redirect.valid #= false
  }

  private def await(cd: ClockDomain, maxCycles: Int, clue: String)(cond: => Boolean): Unit = {
    var cycles = 0
    while (!cond && cycles < maxCycles) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cond, s"$clue (not reached within $maxCycles cycles)")
  }

  test("MMU-on resident L1I keeps useful decode bubble-free and restarts in-order after redirect",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      val walkerMem = new m68k040.sim.DcacheClientMemAgent(dut.walkPort, cd)
      installMapping(walkerMem)
      val pageWords = Seq.tabulate(1024) { i =>
        Seq(0x0a40, 0x1000 | (i & 0x0fff))
      }.flatten
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, PhysPage, pageWords)

      dut.ic.logic.invalidateAll #= false
      dut.probe.logic.feedOut.ready #= false
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.redirect.payload #= 0
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.resume.payload #= 0

      var cycle = 0
      var iArCount = 0
      var walkArCount = 0
      var walkStarts = 0
      val walkArs = ArrayBuffer.empty[Long]
      var capture = false
      val commands = ArrayBuffer.empty[FetchEvent]
      val responses = ArrayBuffer.empty[FetchEvent]
      val groups = ArrayBuffer.empty[Group]
      cd.onSamplings {
        cycle += 1
        if (dut.ic.logic.axi.ar.valid.toBoolean && dut.ic.logic.axi.ar.ready.toBoolean)
          iArCount += 1
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) {
          walkArCount += 1
          walkArs += dut.walkPort.logic.cmd.payload.paddr.toLong
        }
        if (dut.itlb.logic.walker.io.start.toBoolean) walkStarts += 1
        if (capture) {
          if (dut.obs.logic.cmdValid.toBoolean && dut.obs.logic.cmdReady.toBoolean)
            commands += FetchEvent(cycle, dut.obs.logic.cmdPc.toLong)
          if (dut.obs.logic.rspValid.toBoolean)
            responses += FetchEvent(cycle, dut.obs.logic.rspPc.toLong)
          if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean) {
            val s1 = dut.probe.logic.s1v.toBoolean
            groups += Group(
              cycle,
              dut.probe.logic.feedOut.payload(0).pc.toLong,
              dut.probe.logic.feedOut.payload(0).words(0).toInt,
              dut.probe.logic.feedOut.payload(0).words(1).toInt,
              s1,
              if (s1) dut.probe.logic.feedOut.payload(1).pc.toLong else -1L,
              if (s1) dut.probe.logic.feedOut.payload(1).words(0).toInt else -1,
              if (s1) dut.probe.logic.feedOut.payload(1).words(1).toInt else -1)
          }
        }
      }

      cd.waitSampling(5)
      // Post-reset pokes persist because these are architectural control registers.
      dut.ic.logic.prefetchEnable #= false
      dut.ctrl.logic.urp #= Root
      dut.ctrl.logic.srp #= Root
      dut.ctrl.logic.mmuEnable #= true
      cd.waitSampling(2)

      // Warm four adjacent lines with downstream blocked. Each redirect flushes the
      // prior IBuf; waiting for non-empty IBuf plus ringCount=0 proves the demand
      // response landed and every accepted request retired before the next warm arm.
      val warmLines = (0 until 4).map(i => VirtPage + i * 64L)
      for (pc <- warmLines) {
        pulseRedirect(dut, cd, pc)
        await(cd, 500, f"warm line 0x$pc%x") {
          dut.fa.logic.ibuf.count.toInt > 0 && dut.fa.logic.ringCount.toInt == 0
        }
        cd.waitSampling(2)
      }
      assert(walkStarts == 1,
        s"one VPN must require exactly one three-level ITLB walk, got $walkStarts walk " +
          s"launches (walk-port transactions: ${walkArs.map(a => f"0x$a%x")})")
      assert(walkArs.toSeq == ColdWalkPortTxns,
        s"walk-port transactions ${walkArs.map(a => f"0x$a%x")} != the expected cold " +
          s"search + U-drain RMW read ${ColdWalkPortTxns.map(a => f"0x$a%x")}")

      // Phase A: clean resident restart. The first useful packet is N+4, then eight
      // two-wide, two-word groups must fire on eight consecutive cycles. This consumes
      // all four fetched words per cycle, so a response bubble cannot hide in the IBuf.
      dut.probe.logic.feedOut.ready #= false
      pulseRedirect(dut, cd, VirtPage)
      val phaseAStart = cycle
      commands.clear(); responses.clear(); groups.clear()
      val iArBeforeA = iArCount
      val walkArBeforeA = walkArCount
      val walkStartsBeforeA = walkStarts
      capture = true
      dut.probe.logic.feedOut.ready #= true
      await(cd, 80, "eight useful resident groups") { groups.size >= 8 }

      val aGroups = groups.take(8)
      assert(aGroups.head.cycle - phaseAStart == 4,
        s"resident restart first-use latency=${aGroups.head.cycle - phaseAStart}, expected 4")
      assert(aGroups.map(_.cycle).sliding(2).forall(w => w(1) == w(0) + 1),
        s"useful resident feed bubbled: ${aGroups.map(_.cycle)}")
      for ((g, i) <- aGroups.zipWithIndex) {
        val pc0 = VirtPage + i * 8L
        val pc1 = pc0 + 4
        assert(g.slot1, s"cycle ${g.cycle}: warm two-word stream lost slot1")
        assert(g.pc0 == pc0 && g.pc1 == pc1,
          f"cycle ${g.cycle}: got PCs 0x${g.pc0}%x/0x${g.pc1}%x, expected 0x$pc0%x/0x$pc1%x")
        assert(g.op0 == opcodeAt(pc0) && g.op1 == opcodeAt(pc1) &&
          g.ext0 == extensionAt(pc0) && g.ext1 == extensionAt(pc1),
          f"cycle ${g.cycle}: opcode/extension order mismatch at 0x$pc0%x")
      }
      assert(iArCount == iArBeforeA,
        s"resident phase A issued an I-cache refill: $iArBeforeA -> $iArCount")
      assert(walkStarts == walkStartsBeforeA && walkArCount == walkArBeforeA,
        s"resident phase A re-walked a hot ITLB entry: walk launches $walkStartsBeforeA " +
          s"-> $walkStarts, walk-port transactions $walkArBeforeA -> $walkArCount")

      // The first three target commands/responses are the unthrottled pipeline fill.
      // They must be II=1 and associated in order at fixed latency two.
      val aCmd = commands.filter(e => e.pc >= VirtPage && e.pc < VirtPage + 64).take(3)
      val aRsp = responses.filter(e => e.pc >= VirtPage && e.pc < VirtPage + 64).take(3)
      assert(aCmd.map(_.cycle).sliding(2).forall(w => w(1) == w(0) + 1),
        s"initial target commands were not II=1: $aCmd")
      assert(aRsp.map(_.cycle).sliding(2).forall(w => w(1) == w(0) + 1),
        s"initial target responses were not II=1: $aRsp")
      assert(aCmd.size == 3 && aRsp.size == 3,
        s"expected three command/response timing samples, got cmd=$aCmd rsp=$aRsp")
      for ((cmd, rsp) <- aCmd.zip(aRsp)) {
        assert(rsp.pc == cmd.pc && rsp.cycle - cmd.cycle == 2,
          s"resident association/latency mismatch: cmd=$cmd rsp=$rsp")
      }

      // Phase B: collide a redirect with a real resident response/replacement cycle.
      // At latency two, the depth-three ring intentionally stays at occupancy two on a
      // bubble-free stream; the standalone ring-turnover suite still proves the full
      // occupancy-three collision. The redirect-edge command here is old-path and born
      // stale. After the flush, only the warm target's exact sequence may reach decode.
      await(cd, 80, "resident turnover opportunity before redirect") {
        dut.fa.logic.ringCount.toInt == 2 &&
          dut.obs.logic.rspValid.toBoolean &&
          dut.obs.logic.cmdValid.toBoolean && dut.obs.logic.cmdReady.toBoolean
      }
      val target = VirtPage + 64
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= target
      sleep(1)
      assert(dut.fa.logic.ringCount.toInt == 2 &&
        dut.obs.logic.rspValid.toBoolean &&
        dut.obs.logic.cmdValid.toBoolean && dut.obs.logic.cmdReady.toBoolean,
        "redirect must collide with a real resident response/replacement")
      val bornStalePc = dut.obs.logic.cmdPc.toLong
      cd.waitSampling()
      val redirectCycle = cycle
      dut.fa.logic.redirect.valid #= false
      val groupMarker = groups.size
      val commandMarker = commands.size
      val iArBeforeB = iArCount
      val walkArBeforeB = walkArCount
      val walkStartsBeforeB = walkStarts

      await(cd, 80, "eight useful target groups after redirect") {
        groups.size >= groupMarker + 8
      }
      val bGroups = groups.slice(groupMarker, groupMarker + 8)
      assert(bGroups.head.cycle - redirectCycle == 4,
        s"resident redirect restart=${bGroups.head.cycle - redirectCycle} cycles, expected 4")
      assert(bGroups.map(_.cycle).sliding(2).forall(w => w(1) == w(0) + 1),
        s"target feed bubbled after refill: ${bGroups.map(_.cycle)}")
      for ((g, i) <- bGroups.zipWithIndex) {
        val pc0 = target + i * 8L
        val pc1 = pc0 + 4
        assert(g.slot1, s"cycle ${g.cycle}: target two-word stream lost slot1")
        assert(g.pc0 == pc0 && g.pc1 == pc1,
          f"stale/redirect leak: got 0x${g.pc0}%x/0x${g.pc1}%x, expected 0x$pc0%x/0x$pc1%x")
        assert(g.op0 == opcodeAt(pc0) && g.op1 == opcodeAt(pc1) &&
          g.ext0 == extensionAt(pc0) && g.ext1 == extensionAt(pc1),
          f"target opcode/extension order mismatch at 0x$pc0%x")
      }

      val postRedirectCommands = commands.drop(commandMarker)
      assert(postRedirectCommands.nonEmpty && postRedirectCommands.head.pc == target,
        s"first post-redirect accepted command must be target 0x${target.toHexString}: $postRedirectCommands")
      assert(postRedirectCommands.head.cycle == redirectCycle + 1,
        s"target command must issue at N+1, got ${postRedirectCommands.head}")
      assert(bornStalePc != target,
        f"collision setup failed: redirect-edge command 0x$bornStalePc%x was not old-path")
      assert(iArCount == iArBeforeB,
        s"resident phase B issued an I-cache refill: $iArBeforeB -> $iArCount")
      assert(walkStarts == walkStartsBeforeB && walkArCount == walkArBeforeB,
        s"resident phase B re-walked the ITLB: walk launches $walkStartsBeforeB -> " +
          s"$walkStarts, walk-port transactions $walkArBeforeB -> $walkArCount")
    }
  }
}
