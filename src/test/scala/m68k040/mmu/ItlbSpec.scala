package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only plugin: drives the I-side TranslationService request + exposes rsp.
  * Also drives the ItlbPlugin's U-write-queue hooks (access robId, commit, flush,
  * PFLUSHA) -- mirrors UmProbePlugin's DTLB treatment (UmWriteSpec.scala) so C6
  * (the ITLB spurious wrong-path write) can be exercised the same way C1 is. */
class ItlbProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[TranslationService]
    val itlb  = host[ItlbPlugin]
    val reqIn = in(TranslationReq())
    val rspOut = out(TranslationRsp())
    val accessRobId = in UInt (6 bits)
    val commitValid = in Bool ()
    val commitId    = in UInt (6 bits)
    val flush       = in Bool ()
    val pflusha     = in Bool ()
    val walkDone    = out Bool ()
    xlate.req.valid      := reqIn.valid
    xlate.req.vpn        := reqIn.vpn
    xlate.req.supervisor := reqIn.supervisor
    xlate.req.write      := reqIn.write
    rspOut.ready     := xlate.rsp.ready
    rspOut.ppn       := xlate.rsp.ppn
    rspOut.cacheMode := xlate.rsp.cacheMode
    rspOut.fault     := xlate.rsp.fault
    itlb.umAccessRobId := accessRobId
    itlb.umCommitValid := commitValid
    itlb.umCommitId    := commitId
    itlb.umFlush       := flush
    itlb.flushAll      := pflusha
    walkDone           := itlb.logic.walker.io.done
  }
}

/** Directed tests for the ItlbPlugin (instruction-side TLB + table walker behind
  * TranslationService, reading the shared MmuControlService):
  *  - MMU disabled  -> identity passthrough (ppn=vpn, ready, cacheable, no fault)
  *  - MMU enabled + page table: first lookup MISSES -> walk -> fill -> ready+PPN
  *  - second lookup of the same VPN -> 1-cycle TLB HIT (no walk)
  *  - a non-resident page -> rsp.fault (flagged) */
class ItlbSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val itlb = new ItlbPlugin()
    val probe = new ItlbProbePlugin()
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe)) }
    def walkerAxi = itlb.walkerAxi
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff
  def buildTable(mem: BehavioralMemAgent, va: Long, ppn: Long, resident: Boolean = true): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    var pd = (ppn << 12) & 0xfffff000L
    pd |= (if (resident) 0x1L else 0x0L)
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, pd)
  }
  def lookup(dut: Dut, cd: ClockDomain, vpn: Long): (Boolean, Long, Boolean) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= false
    dut.probe.logic.reqIn.supervisor #= false
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    sleep(1)
    (dut.probe.logic.rspOut.ready.toBoolean, dut.probe.logic.rspOut.ppn.toLong, dut.probe.logic.rspOut.fault.toBoolean)
  }

  test("ITLB: MMU disabled -> identity passthrough", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)
      val (ready, ppn, fault) = lookup(dut, cd, 0x54321L)
      assert(ready, "disabled MMU must be ready")
      assert(ppn == 0x54321L, f"identity ppn: got 0x$ppn%x")
      assert(!fault, "no fault when disabled")
    }
  }

  test("ITLB registered miss->walker trigger: single-outstanding while req held (exactly one walk)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      val va = 0x00402000L
      buildTable(mem, va, ppn = 0x12345L)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      // Count every walker AR burst. With the registered trigger, holding the
      // fetch request valid across the multi-cycle walk (as the I-cache does on a
      // miss) must launch EXACTLY ONE walk -> EXACTLY 3 reads (root/ptr/page), no
      // double-walk and no dropped miss.
      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1 } }

      // present the miss and HOLD it valid throughout (the registered trigger must
      // still pulse start exactly once even with the live req held high).
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      var guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      sleep(1)
      assert(dut.probe.logic.rspOut.ready.toBoolean, "held miss resolves (ready) after the walk")
      assert(!dut.probe.logic.rspOut.fault.toBoolean, "resident page: no fault")
      assert(dut.probe.logic.rspOut.ppn.toLong == 0x12345L, f"walked ppn: got 0x${dut.probe.logic.rspOut.ppn.toLong}%x")
      assert(arCount == 3, s"single-outstanding: exactly one 3-level walk while req held; got $arCount ARs")

      // keep holding the (now-resolved) request a few cycles: no spurious re-walk.
      cd.waitSampling(8)
      assert(arCount == 3, s"no re-walk while the resolved req stays valid; got $arCount ARs")
    }
  }

  test("ITLB: miss -> walk -> fill -> hit; non-resident -> fault", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      val va = 0x00802000L
      buildTable(mem, va, ppn = 0xABCDEL)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1 } }

      val (r1, p1, f1) = lookup(dut, cd, vpnOf(va))
      assert(r1 && !f1, "first lookup resolves without fault")
      assert(p1 == 0xABCDEL, f"walked ppn: got 0x$p1%x expected 0xABCDE")
      val afterFirst = arCount
      assert(afterFirst >= 3, s"a 3-level walk issues >=3 reads; got $afterFirst")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      val (r2, p2, f2) = lookup(dut, cd, vpnOf(va))
      assert(r2 && !f2 && p2 == 0xABCDEL, "second lookup hits with correct ppn")
      assert(arCount == afterFirst, s"TLB hit must issue no walk: $afterFirst -> $arCount")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      val vaBad = 0x01006000L
      buildTable(mem, vaBad, ppn = 0x55555L, resident = false)
      val (r3, _, f3) = lookup(dut, cd, vpnOf(vaBad))
      assert(r3, "faulting walk still resolves (ready)")
      assert(f3, "non-resident page must flag rsp.fault")
    }
  }

  // C6 regression (fmax-closure-fanout MMU-walker review): before this fix,
  // `ItlbPlugin`'s deferred U-write allocation (`umq.io.alloc.valid`) had NO
  // poison of any kind -- not even a `flushAll` (PFLUSHA) gate. A walk still
  // in flight when a backend flush (`umFlush`) landed on it would, on
  // completion, still allocate a real deferred U-write queue entry tagged with
  // the now-squashed access's robId. `UmWriteQueue.commit` only marks an entry
  // committed if it is ALREADY allocated at the exact cycle a commit pulse for
  // its robId arrives -- the late allocation just sits uncommitted (the
  // earlier `io.flush` pulse already passed before this entry existed) until
  // the ROB *recycles* that same robId (<=64 retires later), at which point an
  // unrelated, later instruction's OWN commit pulse drains it: a real
  // architectural memory write performed on behalf of a wrong-path fetch.
  //
  // This test proves that shape is closed (part 1), AND that the ITLB-side
  // mirror of C1 is also closed: `ItlbPlugin` has TWO caching layers a poisoned
  // walk could taint -- the TLB array itself (`tlb.io.fillValid`, mirroring
  // DtlbPlugin exactly) AND its own 1-entry post-walk result latch
  // (`latchValid`/`latchMatch`), which DtlbPlugin has no equivalent of. Gating
  // only the TLB fill is not sufficient on this side: without also poisoning
  // the latch's persistence, a later access to the SAME page would still hit
  // the stale latch entry (not the TLB) and skip re-walking, silently losing U
  // exactly like the un-gated TLB fill would have (part 2).
  test("C6: flush during an active ITLB walk poisons its late U allocation AND its ATC/latch caching",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      val va = 0x02003000L
      buildTable(mem, va, ppn = 0x33333L)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1 } }

      // Fetch (robId 40): wait until the walk is genuinely active, then squash
      // before completion -- the exact collision C6 describes.
      dut.probe.logic.accessRobId #= 40
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      var guard = 0
      while (!(dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) && guard < 100) {
        cd.waitSampling(); guard += 1
      }
      assert(guard < 100, "fetch walk never launched before the flush")
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false

      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "poisoned fetch walk still returns its tagged result")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(4)

      // Part 1: recycling/committing the SAME robId later (as if an unrelated
      // later instruction reused it) must NOT drain a spurious descriptor write.
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 40
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      cd.waitSampling(30)
      val pageAddr = PAGT + pageIdx(va) * 4
      assert(mem.peekByte(pageAddr + 3) == 0x01,
        "a walk completing after flush must not allocate or drain a U write for an already-squashed robId")

      // Part 2: a genuinely NEW fetch (fresh robId, no flush) to the SAME page
      // must perform a real re-walk -- neither the TLB nor the 1-entry result
      // latch may still be serving the poisoned walk's cached result.
      val beforeSecond = arCount
      dut.probe.logic.accessRobId #= 41
      val (r2, p2, f2) = lookup(dut, cd, vpnOf(va))
      assert(r2 && !f2 && p2 == 0x33333L, "second fetch to the same page must still resolve correctly")
      assert(arCount > beforeSecond,
        s"a fetch immediately after a poisoned walk to the SAME page must re-walk (neither the TLB nor the " +
        s"result latch may cache the poisoned walk's result): ARs $beforeSecond -> $arCount")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 41
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      guard = 0
      while (mem.peekByte(pageAddr + 3) != 0x09 && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(mem.peekByte(pageAddr + 3) == 0x09,
        f"post-re-walk commit must set U (0x09); got 0x${mem.peekByte(pageAddr + 3)}%x")
    }
  }
}
