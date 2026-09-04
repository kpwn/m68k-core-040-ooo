package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import m68k040.sim.{DcacheClientMemAgent, WalkerDcacheSimIo}
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
    // The table walker is a DcacheService CLIENT now, not an AXI master. This DUT hosts
    // no DcachePlugin, so it exposes the walker's client port pair as its own IO and lets
    // `DcacheClientMemAgent` answer it out of a SparseMemory -- the direct replacement for
    // attaching a DcacheClientMemAgent to the retired `walkerAxi`.
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
  def pokeWordLE(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff
  def buildTable(mem: DcacheClientMemAgent, va: Long, ppn: Long, resident: Boolean = true): Unit = {
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

  // Task #TTR-off-gating fix: like `lookup`, but also returns the resolved cacheMode
  // -- needed to distinguish "TTR-transparent INHIBITED" from the mmuEnable=False
  // fallback's fixed WRITETHROUGH, which the plain 3-tuple `lookup` can't see.
  // Returns cacheMode as a String (rather than the raw SpinalEnumCraft), read
  // immediately at capture time -- matching TlbSpec.lookup's established pattern
  // of resolving the enum comparison/representation AT THE READ POINT, not passing
  // the live simulation-backed enum handle across a function-return boundary.
  def lookupWithMode(dut: Dut, cd: ClockDomain, vpn: Long, supervisor: Boolean = false): (Boolean, Long, Boolean, String) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= false
    dut.probe.logic.reqIn.supervisor #= supervisor
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    sleep(1)
    val ready = dut.probe.logic.rspOut.ready.toBoolean
    val ppn   = dut.probe.logic.rspOut.ppn.toLong
    val fault = dut.probe.logic.rspOut.fault.toBoolean
    val mode  = dut.probe.logic.rspOut.cacheMode.toEnum.toString
    (ready, ppn, fault, mode)
  }

  // Builds an ITT register value per MC68040 UM S3.1.2 / TtMatch's field layout:
  //   [31:24] base  [23:16] mask (1=don't-care)  [15] E  [14:13] S  [6:5] CM
  // `sBits`: 0="00" user-only, 1="01" supervisor-only, 2/3="1x" match either mode.
  def buildTtr(base: Int, mask: Int, enable: Boolean, sBits: Int, inhibited: Boolean): Long = {
    var v = 0L
    v |= (base.toLong & 0xff) << 24
    v |= (mask.toLong & 0xff) << 16
    if (enable) v |= (1L << 15)
    v |= (sBits.toLong & 0x3) << 13
    if (inhibited) v |= (1L << 6)   // CM[1] (bit 6) = non-cacheable/inhibited
    v
  }

  test("ITLB: MMU disabled -> identity passthrough", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new DcacheClientMemAgent(dut.walkPort, cd)
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
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
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
        if (dut.walkPort.cmd.valid.toBoolean && dut.walkPort.cmd.ready.toBoolean) arCount += 1 } }

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
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
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
        if (dut.walkPort.cmd.valid.toBoolean && dut.walkPort.cmd.ready.toBoolean) arCount += 1 } }

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
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
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
        if (dut.walkPort.cmd.valid.toBoolean && dut.walkPort.cmd.ready.toBoolean) arCount += 1 } }

      // Fetch (robId 40): wait until the walk is genuinely active, then squash
      // before completion -- the exact collision C6 describes.
      dut.probe.logic.accessRobId #= 40
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      var guard = 0
      while (!(dut.walkPort.cmd.valid.toBoolean && dut.walkPort.cmd.ready.toBoolean) && guard < 100) {
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

  // Fix for the TTR/mmuEnable gating bug (found via live-hardware boot investigation,
  // docs/BUG_video_driver_selection.md, commit 4339fa7; same fix as DtlbPlugin's
  // DTT0/DTT1, applied here to ITT0/ITT1): ITT0/ITT1 must apply regardless of
  // mmuEnable -- gated only by their own E bit, per real 68040 semantics. This pins
  // the case the bug fixes: MMU OFF, ITT0 configured (E=1) and matching the fetch
  // VA -- the response must reflect ITT0's OWN cacheMode (INHIBITED here), NOT the
  // mmuEnable=False fallback's fixed WRITETHROUGH.
  test("ITLB: MMU disabled + ITT0 match -> TTR cacheMode wins, not the disabled-MMU WRITETHROUGH default", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      // mmuEnable stays False (default) -- this is the whole point: ITT0 must still
      // apply. VA 0x5000_1000 mirrors the real VIA/SCC/DAFB MMIO window (base 0x50,
      // mask=0x00 -> exact top-byte match), S="1x" (match either mode), CM=inhibited.
      val va = 0x50001000L
      dut.ctrl.logic.itt0 #= buildTtr(base = 0x50, mask = 0x00, enable = true, sBits = 2, inhibited = true)
      cd.waitSampling(2)

      val (ready, ppn, fault, mode) = lookupWithMode(dut, cd, vpnOf(va))
      assert(ready, "ITT0-transparent fetch must be ready (bypasses walker/TLB)")
      assert(!fault, "a TTR-transparent access never faults")
      assert(ppn == vpnOf(va), f"TTR hit is PA=VA (identity): got 0x$ppn%x expected 0x${vpnOf(va)}%x")
      assert(mode == "INHIBITED",
        s"ITT0's own cacheMode (INHIBITED) must win over the mmuEnable=False default (WRITETHROUGH); got $mode")
    }
  }

  // Mirror negative case: MMU still off, ITT0 configured but its OWN E bit clear ->
  // must fall back to the pre-existing identity/WRITETHROUGH behavior unchanged.
  test("ITLB: MMU disabled + ITT0 configured but E bit clear -> unchanged WRITETHROUGH identity fallback", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.accessRobId #= 0
      dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
      dut.probe.logic.flush #= false
      dut.probe.logic.pflusha #= false
      cd.waitSampling(4)

      val va = 0x50001000L
      // Same base/mask/CM as the positive case, but E=0 (disabled) -- must NOT match.
      dut.ctrl.logic.itt0 #= buildTtr(base = 0x50, mask = 0x00, enable = false, sBits = 2, inhibited = true)
      cd.waitSampling(2)

      val (ready, ppn, fault, mode) = lookupWithMode(dut, cd, vpnOf(va))
      assert(ready, "disabled MMU (no TTR match) must still be ready")
      assert(!fault, "no fault when disabled and no TTR matched")
      assert(ppn == vpnOf(va), "identity ppn unchanged")
      assert(mode == "WRITETHROUGH",
        s"an E=0 TTR must not match -- fallback must remain WRITETHROUGH; got $mode")
    }
  }
}
