package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{CacheMode, DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.ls.BehavioralMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only plugin: drives the DTranslationService request + exposes the response. */
class DtlbProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val reqIn = in(TranslationReq())
    val rspOut = out(TranslationRsp())
    val requestIssued = RegInit(False)
    xlate.req.valid      := reqIn.valid && !requestIssued
    xlate.req.payload.vpn        := reqIn.vpn
    xlate.req.payload.supervisor := reqIn.supervisor
    xlate.req.payload.write      := reqIn.write
    xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
    // Compatibility shell for the older unit-test IO: hold the tagged response
    // until the driver lowers reqIn.valid, preventing a held request from being
    // accepted again on the response's accept-last cycle.
    xlate.rsp.ready   := !reqIn.valid
    rspOut.ready      := xlate.rsp.valid
    rspOut.ppn        := xlate.rsp.payload.ppn
    rspOut.cacheMode  := xlate.rsp.payload.cacheMode
    rspOut.fault      := xlate.rsp.payload.fault
    when(xlate.req.fire) { requestIssued := True }
    when(!reqIn.valid)   { requestIssued := False }
  }
}

/** Directed tests for the DtlbPlugin (TLB + walker behind DTranslationService):
  *  - MMU disabled  -> identity passthrough (ppn=vpn, ready, cacheable, no fault)
  *  - MMU enabled + page table: first lookup MISSES -> walk -> fill -> ready+correct PPN
  *  - second lookup of the same VPN -> 1-cycle TLB HIT (no walk)
  *  - a non-resident page -> rsp.fault (flagged) */
class DtlbSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new DtlbProbePlugin()
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe)) }
    def walkerAxi = dtlb.walkerAxi   // full Axi4 master exposed by the plugin
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

  def lookup(dut: Dut, cd: ClockDomain, vpn: Long, write: Boolean = false): (Boolean, Long, Boolean) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= write
    dut.probe.logic.reqIn.supervisor #= false
    // advance one edge so the new request propagates before polling (the prior
    // request's `ready` is still asserted this timestep — check-first would race).
    cd.waitSampling()
    // wait until translation resolves (ready), bounded
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    sleep(1)
    val ready = dut.probe.logic.rspOut.ready.toBoolean
    val ppn   = dut.probe.logic.rspOut.ppn.toLong
    val fault = dut.probe.logic.rspOut.fault.toBoolean
    (ready, ppn, fault)
  }

  test("MMU disabled -> identity passthrough", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(4)
      // mmuEnable defaults False
      val (ready, ppn, fault) = lookup(dut, cd, 0x54321L)
      assert(ready, "disabled MMU must be ready")
      assert(ppn == 0x54321L, f"identity ppn: got 0x$ppn%x")
      assert(!fault, "no fault when disabled")
    }
  }

  test("registered miss->walker trigger: single-outstanding while req held (exactly one walk)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(4)

      val va = 0x00402000L
      buildTable(mem, va, ppn = 0x12345L)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      // Count every walker AR burst. With the registered trigger, holding the
      // request valid across the multi-cycle walk (as the LS-EU does on a miss)
      // must launch EXACTLY ONE walk -> EXACTLY 3 reads (root/ptr/page), no
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

  test("MMU enabled: miss -> walk -> fill -> hit; non-resident -> fault", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(4)

      // enable MMU + set root pointer + build a table mapping VA 0x00802000 -> PPN 0xABCDE
      val va = 0x00802000L
      buildTable(mem, va, ppn = 0xABCDEL)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      // count walker AR bursts to prove the second lookup is a TLB hit (no walk)
      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1 } }

      // first lookup: TLB miss -> walk -> fill -> ready with the translated PPN
      val (r1, p1, f1) = lookup(dut, cd, vpnOf(va))
      assert(r1 && !f1, "first lookup resolves without fault")
      assert(p1 == 0xABCDEL, f"walked ppn: got 0x$p1%x expected 0xABCDE")
      val afterFirst = arCount
      assert(afterFirst >= 3, s"a 3-level walk issues >=3 reads; got $afterFirst")
      // drop the request a cycle so the latch path isn't what we measure
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // second lookup of the SAME vpn: TLB hit -> no new walk reads
      val (r2, p2, f2) = lookup(dut, cd, vpnOf(va))
      assert(r2 && !f2 && p2 == 0xABCDEL, "second lookup hits with correct ppn")
      assert(arCount == afterFirst, s"TLB hit must issue no walk: $afterFirst -> $arCount")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // a different VA whose page descriptor is non-resident -> fault flagged
      val vaBad = 0x01006000L
      buildTable(mem, vaBad, ppn = 0x55555L, resident = false)
      val (r3, _, f3) = lookup(dut, cd, vpnOf(vaBad))
      assert(r3, "faulting walk still resolves (ready)")
      assert(f3, "non-resident page must flag rsp.fault")
    }
  }

  // Task #136: PFLUSHA's real effect is `DtlbPlugin.flushAll` clearing the TLB array
  // (via `tlb.io.invalidateAll`, already proven single-cycle in TlbSpec) AND the
  // 1-entry walk-result latch (which otherwise bypasses the TLB array on a same-VPN
  // hit even after the array itself is cleared). This test proves BOTH halves matter:
  // fill the TLB, confirm a hit (no walk), pulse flushAll, confirm the NEXT lookup of
  // the SAME vpn re-walks (proving neither the array nor the latch served it stale).
  test("task #136: flushAll (PFLUSHA) clears TLB + walk-result latch -> next lookup re-walks", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      dut.dtlb.flushAll #= false
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

      // first lookup: miss -> walk -> fill.
      val (r1, p1, f1) = lookup(dut, cd, vpnOf(va))
      assert(r1 && !f1 && p1 == 0xABCDEL, "first lookup walks and resolves correctly")
      val afterFirst = arCount
      assert(afterFirst >= 3, s"a 3-level walk issues >=3 reads; got $afterFirst")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // second lookup of the SAME vpn: TLB hit, no new walk (sanity — matches the
      // existing miss->walk->fill->hit test, just re-confirmed before the flush).
      val (r2, p2, f2) = lookup(dut, cd, vpnOf(va))
      assert(r2 && !f2 && p2 == 0xABCDEL, "second lookup hits with correct ppn")
      assert(arCount == afterFirst, s"TLB hit must issue no walk before the flush: $afterFirst -> $arCount")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // pulse flushAll for exactly one cycle (mirrors how the real S_APPLY FSM pulses
      // ExceptionUnit.sysFlushAllValid for a single cycle).
      dut.dtlb.flushAll #= true
      cd.waitSampling()
      dut.dtlb.flushAll #= false
      cd.waitSampling(2)

      // third lookup of the SAME vpn: must walk again (both the TLB array AND the
      // walk-result latch were cleared) -> arCount increases, and the result is still
      // correct (the page table itself is untouched, only the cache was flushed).
      val (r3, p3, f3) = lookup(dut, cd, vpnOf(va))
      assert(r3 && !f3 && p3 == 0xABCDEL, "post-flush lookup re-resolves correctly")
      assert(arCount > afterFirst, s"post-flushAll lookup of the same vpn must re-walk: $afterFirst -> $arCount")
    }
  }

  // Task #137: bisecting a full-core lock-step failure — a write to a resident,
  // write-protected (W bit set) page did not fault in ExecuteLockStepSpec's new WP
  // test, even though TableWalkerSpec's ISOLATED walker test already proves the
  // walker itself correctly flags WRITE_PROTECT. This test checks the SAME thing at
  // the DtlbPlugin (TLB+walker+permFault) layer, one level up from the raw walker
  // but still far short of the full core (no LS-EU/AGU in between) — narrows down
  // whether the bug is in DtlbPlugin or specifically in how the full pipeline drives
  // TranslationReq.write for a real STORE.
  test("task #137: write-protected resident page -> write faults, read does not", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(4)

      val va = 0x00902000L
      pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      val ppn = 0x77L
      val wpDesc = ((ppn << 12) & 0xfffff000L) | 0x4L | 0x1L   // resident, W=1
      pokeWordLE(mem, PAGT + pageIdx(va) * 4, wpDesc)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= ROOT
      dut.ctrl.logic.srp   #= ROOT
      cd.waitSampling(2)

      // WRITE first (fresh TLB, forces a walk on the write access itself).
      val (rw, pw, fw) = lookup(dut, cd, vpnOf(va), write = true)
      assert(rw, "write to WP page still resolves (ready)")
      assert(fw, "write to a write-protected resident page must flag rsp.fault")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // READ of the SAME page must NOT fault.
      val (rr, pr, fr) = lookup(dut, cd, vpnOf(va), write = false)
      assert(rr && !fr, "read of a write-protected (but resident) page must NOT fault")
      assert(pr == ppn, f"read ppn: got 0x$pr%x expected 0x$ppn%x")
    }
  }
}
