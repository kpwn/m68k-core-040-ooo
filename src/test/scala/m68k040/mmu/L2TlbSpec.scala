package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.{DTranslationService, MmuControlService, TranslationService}
import m68k040.sim.DcacheClientMemAgent
import spinal.lib.sim.SparseMemory
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Shared L2 TLB (`CPU_L2_TLB`): directed coverage on a DTLB + ITLB pair sharing one
  * `MmuControlPlugin` (which owns the L2) and one descriptor memory.
  *
  * Walks are classified at `walker.io.done`: "L2 hit" (`walker.io.l2Hit`, no descriptor
  * read) vs "full walk". Every DTLB access is `preCommitted` so the deferred U/M write
  * drains unaided (no ROB in this harness). */
class L2TlbSpec extends AnyFunSuite {

  class DProbe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val mctrl = host[MmuControlService]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val pflusha = in Bool ()
      /** 0 none, 1 SRP, 2 URP, 3 TC.P (same value), 4 TC.E (same value), 5 DTT0, 6 DTT1, 7 ITT0, 8 ITT1 */
      val ctrlWrite = in UInt (4 bits)
      val ctrlVal   = in UInt (32 bits)
      val requestIssued = RegInit(False)
      xlate.req.valid              := reqIn.valid && !requestIssued
      xlate.req.payload.vpn        := reqIn.vpn
      xlate.req.payload.supervisor := reqIn.supervisor
      xlate.req.payload.write      := reqIn.write
      xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
      xlate.rsp.ready   := !reqIn.valid
      rspOut.ready      := xlate.rsp.valid
      rspOut.ppn        := xlate.rsp.payload.ppn
      rspOut.cacheMode  := xlate.rsp.payload.cacheMode
      rspOut.fault      := xlate.rsp.payload.fault
      when(xlate.req.fire) { requestIssued := True }
      when(!reqIn.valid)   { requestIssued := False }
      dtlb.flushAll := pflusha
      dtlb.umAccessPreCommitted := True
      switch(ctrlWrite) {
        is(1) { mctrl.setSrp.valid := True; mctrl.setSrp.payload := ctrlVal }
        is(2) { mctrl.setUrp.valid := True; mctrl.setUrp.payload := ctrlVal }
        is(3) { mctrl.setPageSize.valid := True; mctrl.setPageSize.payload := ctrlVal(0) }
        is(4) { mctrl.setEnable.valid := True; mctrl.setEnable.payload := ctrlVal(0) }
        is(5) { mctrl.setDtt0.valid := True; mctrl.setDtt0.payload := ctrlVal }
        is(6) { mctrl.setDtt1.valid := True; mctrl.setDtt1.payload := ctrlVal }
        is(7) { mctrl.setItt0.valid := True; mctrl.setItt0.payload := ctrlVal }
        is(8) { mctrl.setItt1.valid := True; mctrl.setItt1.payload := ctrlVal }
      }
      val walkDone = out Bool (); walkDone := dtlb.logic.walker.io.done
      val walkL2Hit = out Bool ()
      walkL2Hit := (if (dtlb.logic.l2Svc.isDefined) dtlb.logic.walker.io.l2Hit else False)
      /** A "software" store (or DMA) to physical address `extSnoopAddr`. */
      val extSnoop = in Bool ()
      val extSnoopAddr = in UInt (32 bits)
      host.get[L2TlbService].filter(_.l2TlbEnabled).foreach { l2 =>
        l2.l2TlbExtSnoop.valid := extSnoop
        l2.l2TlbExtSnoop.payload := extSnoopAddr
      }
    }
  }

  class IProbe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[TranslationService]
      val itlb  = host[ItlbPlugin]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      xlate.req.valid      := reqIn.valid
      xlate.req.vpn        := reqIn.vpn
      xlate.req.supervisor := reqIn.supervisor
      xlate.req.write      := reqIn.write
      rspOut.ready     := xlate.rsp.ready
      rspOut.ppn       := xlate.rsp.ppn
      rspOut.cacheMode := xlate.rsp.cacheMode
      rspOut.fault     := xlate.rsp.fault
      val walkDone = out Bool (); walkDone := itlb.logic.walker.io.done
      val walkL2Hit = out Bool ()
      walkL2Hit := (if (itlb.logic.l2Svc.isDefined) itlb.logic.walker.io.l2Hit else False)
    }
  }

  class Dut(l2Entries: Int, survive: Boolean = false, granules: Int = 32) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin(l2TlbEntries = l2Entries, l2TlbSurvive = survive,
                                    l2TlbGranules = granules)
    // L1 DTLB victim buffer pinned OFF: every count below assumes the L1 DTLB alone
    // holds at most 32 translations (`evictD`'s 64 fillers push a page out, and a
    // 64-page pass must miss the L1 at least 32 times). The shipping default adds a
    // 32-entry victim buffer (64 resident), which turns those L1 misses into
    // promotes and the L2 counts into zeros. `DtlbVictimSpec` covers the buffer.
    val dtlb = new DtlbPlugin(victimEntries = 0)
    val itlb = new ItlbPlugin()
    val dprobe = new DProbe()
    val iprobe = new IProbe()
    val dWalk = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    val iWalk = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, itlb,
      dprobe, iprobe, dWalk, iWalk)) }
  }

  // NOT `lazy val`: a lazy val holds the spec's monitor while SpinalHDL elaborates on
  // another thread, which then needs the same monitor for the inner `Dut` class -- deadlock.
  private val compiled = scala.collection.mutable.Map.empty[String, SimCompiled[Dut]]
  private def comp(k: String, mk: => Dut): SimCompiled[Dut] =
    compiled.getOrElseUpdate(k, SimConfig.withVerilator.compile(mk))
  private def compiledOn  = comp("on", new Dut(512))
  private def compiledOff = comp("off", new Dut(0))
  private def compiledSurvive = comp("survive", new Dut(512, survive = true))
  private def compiledSurvive4 = comp("survive4", new Dut(512, survive = true, granules = 4))

  // Tree A (two page tables: the 64 test pages and 64 filler pages), tree B (test pages only).
  // The two roots are the Q700 ROM's (24-bit / 32-bit mode swap at 0x40803f5c).
  private val ROOT_A = 0x7fcc00L; private val PTRT_A = 0x11000L
  private val PAGT_A = 0x12000L; private val PAGF_A = 0x13000L
  private val ROOT_B = 0x7ffa00L; private val PTRT_B = 0x21000L; private val PAGT_B = 0x22000L
  private val VPN0   = 0x02000L     // test pages VPN0 .. VPN0+63 (one page table)
  private val FILL0  = 0x02040L     // filler pages FILL0 .. FILL0+63 (the next page table)
  private val N      = 64

  class Env(dut: Dut) {
    val cd = dut.clockDomain
    val shared = SparseMemory()
    val dmem = new DcacheClientMemAgent(dut.dWalk, cd, shared)
    val imem = new DcacheClientMemAgent(dut.iWalk, cd, shared)
    val dp = dut.dprobe.logic
    val ip = dut.iprobe.logic
    var dFull = 0; var dL2 = 0; var iFull = 0; var iL2 = 0
    var faultCount = 0

    def pokeWord(addr: Long, w: Long): Unit =
      for (i <- 0 until 4) shared.write(addr + i, ((w >> (8 * (3 - i))) & 0xff).toByte)
    def peekLow(addr: Long): Int = shared.read(addr + 3).toInt & 0xff
    def leafAddr(pagt: Long, vpn: Long): Long = pagt + (vpn & 0x3f) * 4
    def setLeaf(pagt: Long, vpn: Long, ppn: Long, low: Int): Unit =
      pokeWord(leafAddr(pagt, vpn), ((ppn << 12) & 0xfffff000L) | low)
    def linkTables(root: Long, ptrt: Long, pagt: Long, vpn: Long): Unit = {
      pokeWord(root + ((vpn >> 13) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0xbL)  // UDT resident + U
      pokeWord(ptrt + ((vpn >> 6) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0xbL)
    }
    def ppnA(i: Int, gen: Int = 0): Long = 0x30000L + gen * 0x100 + i
    def ppnB(i: Int): Long = 0x40000L + i

    def init(): Unit = {
      cd.forkStimulus(10)
      dp.reqIn.valid #= false; dp.reqIn.vpn #= 0; dp.reqIn.write #= false; dp.reqIn.supervisor #= true
      dp.pflusha #= false; dp.ctrlWrite #= 0; dp.ctrlVal #= 0
      dp.extSnoop #= false; dp.extSnoopAddr #= 0
      ip.reqIn.valid #= false; ip.reqIn.vpn #= 0; ip.reqIn.write #= false; ip.reqIn.supervisor #= true
      cd.waitSampling(4)                // past the reset: the MMU registers are poked below
      linkTables(ROOT_A, PTRT_A, PAGT_A, VPN0)
      linkTables(ROOT_A, PTRT_A, PAGF_A, FILL0)
      linkTables(ROOT_B, PTRT_B, PAGT_B, VPN0)
      for (i <- 0 until N) {
        setLeaf(PAGT_A, VPN0 + i, ppnA(i), 0x19)   // resident, U, M: no U/M write
        setLeaf(PAGF_A, FILL0 + i, 0x50000L + i, 0x19)
        setLeaf(PAGT_B, VPN0 + i, ppnB(i), 0x19)
      }
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT_A
      dut.ctrl.logic.urp #= ROOT_A
      fork { while (true) { cd.waitSampling()
        if (dp.walkDone.toBoolean) { if (dp.walkL2Hit.toBoolean) dL2 += 1 else dFull += 1 }
        if (ip.walkDone.toBoolean) { if (ip.walkL2Hit.toBoolean) iL2 += 1 else iFull += 1 }
      } }
      cd.waitSampling(600)   // the L2 TLB's reset sweep (512 cycles)
    }

    /** One DTLB access; returns (ppn, fault). */
    def dAccess(vpn: Long, write: Boolean = false, sup: Boolean = true): (Long, Boolean) = {
      dp.reqIn.vpn #= vpn; dp.reqIn.write #= write; dp.reqIn.supervisor #= sup
      dp.reqIn.valid #= true
      var g = 0
      while (!dp.rspOut.ready.toBoolean && g < 600) { cd.waitSampling(); g += 1 }
      assert(dp.rspOut.ready.toBoolean, f"DTLB access to vpn 0x$vpn%05x never answered")
      sleep(1)
      val r = (dp.rspOut.ppn.toLong, dp.rspOut.fault.toBoolean)
      dp.reqIn.valid #= false
      cd.waitSampling(3)
      r
    }
    def iAccess(vpn: Long, sup: Boolean = true): (Long, Boolean) = {
      ip.reqIn.vpn #= vpn; ip.reqIn.supervisor #= sup
      ip.reqIn.valid #= true
      cd.waitSampling()
      var g = 0
      while (!ip.rspOut.ready.toBoolean && g < 600) { cd.waitSampling(); g += 1 }
      assert(ip.rspOut.ready.toBoolean, f"ITLB access to vpn 0x$vpn%05x never answered")
      sleep(1)
      val r = (ip.rspOut.ppn.toLong, ip.rspOut.fault.toBoolean)
      ip.reqIn.valid #= false
      cd.waitSampling(3)
      r
    }
    def ctrl(kind: Int, v: Long): Unit = {
      dp.ctrlWrite #= kind; dp.ctrlVal #= v; cd.waitSampling()
      dp.ctrlWrite #= 0; cd.waitSampling(3)
    }
    def swStore(addr: Long): Unit = {
      dp.extSnoopAddr #= addr; dp.extSnoop #= true; cd.waitSampling()
      dp.extSnoop #= false; cd.waitSampling(3)
    }
    def pflusha(): Unit = { dp.pflusha #= true; cd.waitSampling(); dp.pflusha #= false; cd.waitSampling(3) }
    /** Push the L1 DTLB entries out by touching the 64 filler pages twice. */
    def evictD(): Unit = for (_ <- 0 until 2; i <- 0 until N) dAccess(FILL0 + i)
    def passD(expect: Int => Long, sup: Boolean = true): Unit =
      for (i <- 0 until N) {
        val (ppn, f) = dAccess(VPN0 + i, sup = sup)
        assert(!f, f"unexpected fault at vpn 0x${VPN0 + i}%05x")
        assert(ppn == expect(i), f"vpn 0x${VPN0 + i}%05x -> ppn 0x$ppn%05x, expected 0x${expect(i)}%05x")
      }
    def snap = (dFull, dL2, iFull, iL2)
  }

  test("hit/miss: the second pass over 64 pages is served by the L2 (no descriptor reads)", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i))
      val (f1, h1, _, _) = e.snap
      assert(f1 == N && h1 == 0, s"pass 1: $f1 full walks / $h1 L2 hits, expected $N / 0")
      val loads1 = e.dmem.loadCount
      e.passD(i => e.ppnA(i))
      val (f2, h2, _, _) = e.snap
      info(s"pass 2: full walks ${f2 - f1}, L2 hits ${h2 - h1}, descriptor loads ${e.dmem.loadCount - loads1}")
      assert(f2 == f1, "pass 2 must not full-walk")
      assert(h2 - h1 >= N - 32, "the L1 holds at most 32: at least 32 of 64 must be L2 hits")
      assert(e.dmem.loadCount == loads1, "an L2 hit reads no descriptor")
    }
  }

  test("control: with the L2 off the second pass re-walks", VerilatorTest) {
    compiledOff.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i))
      val f1 = e.dFull
      e.passD(i => e.ppnA(i))
      info(s"L2 off: pass 2 full walks ${e.dFull - f1}")
      assert(e.dFull - f1 >= N - 32 && e.dL2 == 0)
    }
  }

  test("every flush source empties the L2", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      // (name, action, L1 also flushed -> the next pass must see the NEW tree)
      val sources: Seq[(String, () => Unit, Boolean)] = Seq(
        ("PFLUSHA", () => e.pflusha(), true),
        ("SRP",     () => e.ctrl(1, ROOT_A), true),
        ("URP",     () => e.ctrl(2, ROOT_A), true),
        ("TC.P",    () => e.ctrl(3, 0), false),   // same value: L1 keeps, L2 flushes
        ("TC.E",    () => e.ctrl(4, 1), false),
        ("DTT0",    () => e.ctrl(5, 0), true),
        ("DTT1",    () => e.ctrl(6, 0), true),
        ("ITT0",    () => e.ctrl(7, 0), false),
        ("ITT1",    () => e.ctrl(8, 0), false))
      var gen = 0
      e.passD(i => e.ppnA(i, gen)); e.passD(i => e.ppnA(i, gen))   // L2 populated
      for ((name, act, l1Flushed) <- sources) {
        gen += 1
        // Rewrite the tree (software would PFLUSH after this): every leaf changes.
        for (i <- 0 until N) e.setLeaf(PAGT_A, VPN0 + i, e.ppnA(i, gen), 0x19)
        act()
        val (f0, h0, _, _) = e.snap
        if (l1Flushed) e.passD(i => e.ppnA(i, gen))
        else for (i <- 0 until N) e.dAccess(VPN0 + i)
        val (f1, h1, _, _) = e.snap
        info(s"[$name] after flush: full walks ${f1 - f0}, L2 hits ${h1 - h0}")
        assert(h1 == h0, s"[$name] the L2 answered after the flush")
        assert(f1 - f0 >= N - 32, s"[$name] the L1 misses must full-walk")
        // A second pass must now hit the L2 with the NEW translation, and an architectural
        // flush brings everything to the new tree.
        e.pflusha()
        e.passD(i => e.ppnA(i, gen))
        val (_, h2, _, _) = e.snap
        e.passD(i => e.ppnA(i, gen))
        assert(e.snap._2 > h2, s"[$name] the L2 must refill after the flush")
      }
    }
  }

  test("context switch via URP: user pages follow the new root, never the old L2 entries", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i), sup = false); e.passD(i => e.ppnA(i), sup = false)
      e.ctrl(2, ROOT_B)
      e.passD(i => e.ppnB(i), sup = false); e.passD(i => e.ppnB(i), sup = false)
      e.ctrl(2, ROOT_A)
      e.passD(i => e.ppnA(i), sup = false)
      // supervisor (SRP = A) and user (URP) are separate keys
      e.ctrl(2, ROOT_B)
      e.passD(i => e.ppnA(i), sup = true)
      e.passD(i => e.ppnB(i), sup = false)
      e.passD(i => e.ppnA(i), sup = true)
      e.passD(i => e.ppnB(i), sup = false)
      info(s"context switches: full ${e.dFull}, L2 hits ${e.dL2}")
    }
  }

  test("U first use: a walk that sets U is not cached; the next walk is", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      val v = VPN0 + 5
      e.setLeaf(PAGT_A, v, 0x77777L, 0x01)               // resident, U=0, M=0
      e.dAccess(v)
      var g = 0
      while (e.peekLow(e.leafAddr(PAGT_A, v)) != 0x09 && g < 200) { e.cd.waitSampling(); g += 1 }
      assert(e.peekLow(e.leafAddr(PAGT_A, v)) == 0x09, "U must be set by the first walk")
      e.evictD()
      val (f0, h0, _, _) = e.snap
      assert(e.dAccess(v)._1 == 0x77777L)
      val (f1, h1, _, _) = e.snap
      assert(f1 == f0 + 1 && h1 == h0, "the U-setting walk must not have filled the L2")
      e.evictD()
      val (f1b, h1b, _, _) = e.snap
      assert(e.dAccess(v)._1 == 0x77777L)
      assert(e.snap._2 == h1b + 1 && e.snap._1 == f1b, "a walk with no U/M write fills the L2")
    }
  }

  test("M first write: a write to an L2 entry cached with M=0 walks and sets M", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      val v = VPN0 + 9
      val la = e.leafAddr(PAGT_A, v)
      e.setLeaf(PAGT_A, v, 0x66666L, 0x09)               // resident, U=1, M=0
      e.dAccess(v); e.evictD(); e.dAccess(v)               // read walk fills L2 (M=0); read hits
      val (f0, h0, _, _) = e.snap
      assert(h0 >= 1, "the read must hit the L2")
      e.evictD()
      val (f1, h1, _, _) = e.snap
      val (ppn, fault) = e.dAccess(v, write = true)
      assert(ppn == 0x66666L && !fault)
      assert(e.snap._1 == f1 + 1 && e.snap._2 == h1, "a write to an M=0 L2 entry must full-walk")
      var g = 0
      while (e.peekLow(la) != 0x19 && g < 200) { e.cd.waitSampling(); g += 1 }
      assert(e.peekLow(la) == 0x19, f"M must be set: 0x${e.peekLow(la)}%02x")
      // The next write walk (L1 evicted) sees M=1 in memory: no write, fills L2 with M=1.
      e.evictD(); e.dAccess(v, write = true)
      e.evictD()
      val (f2, h2, _, _) = e.snap
      e.dAccess(v, write = true)
      assert(e.snap._2 == h2 + 1 && e.snap._1 == f2, "write to an M=1 L2 entry is an L2 hit")
    }
  }

  test("write-protect and supervisor-only pages fault exactly as the walk does", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      val w = VPN0 + 3
      e.setLeaf(PAGT_A, w, 0x55555L, 0x1d)               // resident, WP, U, M
      e.dAccess(w); e.evictD(); e.dAccess(w)
      e.evictD()
      val (f0, h0, _, _) = e.snap
      val (_, wf) = e.dAccess(w, write = true)
      assert(wf, "write to a WP page must fault")
      assert(e.snap._1 == f0 + 1 && e.snap._2 == h0, "WP write must fall back to the walk")
      e.evictD()
      val (_, rf) = e.dAccess(w)
      assert(!rf, "read of a WP page must not fault")

      val s = VPN0 + 4
      e.setLeaf(PAGT_A, s, 0x44444L, 0x99)               // resident, S, U, M
      e.dAccess(s, sup = true); e.evictD()
      val (_, sf) = e.dAccess(s, sup = true)
      assert(!sf, "supervisor read of an S page")
      val (fu0, hu0, _, _) = e.snap
      val (_, uf) = e.dAccess(s, sup = false)
      assert(uf, "user access to an S page must fault")
      assert(e.snap._1 == fu0 + 1 && e.snap._2 == hu0, "the faulting user access must walk")
      e.evictD()
      val (fu1, hu1, _, _) = e.snap
      assert(e.dAccess(s, sup = false)._2, "still faults (a faulting walk is never cached)")
      assert(e.snap._1 == fu1 + 1 && e.snap._2 == hu1)
    }
  }

  test("shared: DTLB walks fill the L2 for the ITLB", VerilatorTest) {
    compiledOn.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i))
      for (i <- 0 until N) {
        val (ppn, f) = e.iAccess(VPN0 + i)
        assert(!f && ppn == e.ppnA(i), f"ITLB vpn 0x${VPN0 + i}%05x -> 0x$ppn%05x")
      }
      val (_, _, iF, iH) = e.snap
      info(s"ITLB after DTLB fill: full walks $iF, L2 hits $iH")
      assert(iF == 0 && iH == N, "every ITLB miss must be an L2 hit")
      // and an I-side flush empties it for both
      e.ctrl(7, 0)
      val (_, h0, _, _) = e.snap
      for (i <- 0 until N) e.dAccess(VPN0 + i)
      assert(e.snap._2 == h0, "ITT write flushes the shared L2 for the DTLB too")
    }
  }

  // ───────────────────────────── SURVIVE mode ─────────────────────────────

  test("survive: PFLUSHA and MMU-control rewrites do not clear the L2", VerilatorTest) {
    compiledSurvive.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i)); e.passD(i => e.ppnA(i))
      val acts: Seq[(String, () => Unit)] = Seq(
        ("PFLUSHA", () => e.pflusha()), ("SRP same", () => e.ctrl(1, ROOT_A)),
        ("TC.P same", () => e.ctrl(3, 0)), ("TC.E same", () => e.ctrl(4, 1)),
        ("DTT0", () => e.ctrl(5, 0)), ("ITT0", () => e.ctrl(7, 0)))
      for ((name, act) <- acts) {
        act(); e.pflusha()
        val (f0, h0, _, _) = e.snap
        e.passD(i => e.ppnA(i))
        val (f1, h1, _, _) = e.snap
        info(s"[$name + PFLUSHA] full walks ${f1 - f0}, L2 hits ${h1 - h0}")
        assert(f1 == f0 && h1 - h0 == N, s"[$name] every L1 miss after the flush must be an L2 hit")
      }
    }
  }

  test("survive: the ROM's root swap (SRP A <-> B + PFLUSHA) keeps both trees cached", VerilatorTest) {
    compiledSurvive.doSim { dut =>
      val e = new Env(dut); e.init()
      for (round <- 0 until 4) {
        e.ctrl(1, ROOT_A); e.pflusha()
        val (fa, ha, _, _) = e.snap
        e.passD(i => e.ppnA(i))
        e.ctrl(1, ROOT_B); e.pflusha()
        val (fb, hb, _, _) = e.snap
        e.passD(i => e.ppnB(i))
        val (fc, hc, _, _) = e.snap
        info(s"round $round: A full ${fb - fa} hit ${hb - ha}; B full ${fc - fb} hit ${hc - hb}")
        // Round 0 allocates both contexts (each allocation flushes the array once); from
        // round 2 on only direct-mapped set collisions between the two trees may re-walk.
        if (round >= 2) assert(fc - fa <= 4 && hc - ha >= 2 * N - 4, s"round $round must be (almost) all L2 hits")
      }
    }
  }

  test("survive: a store to a descriptor invalidates; a store elsewhere does not", VerilatorTest) {
    compiledSurvive.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i)); e.passD(i => e.ppnA(i))
      // unrelated store (data page): no flush
      e.swStore(0x00700000L); e.pflusha()
      val (f0, h0, _, _) = e.snap
      e.passD(i => e.ppnA(i))
      assert(e.snap._1 == f0 && e.snap._2 - h0 == N, "an unrelated store must not flush")
      // software rewrites page 7's descriptor (new PPN), then PFLUSHA
      val k = 7
      e.setLeaf(PAGT_A, VPN0 + k, 0x7abcdL, 0x19)
      e.swStore(e.leafAddr(PAGT_A, VPN0 + k))
      e.pflusha()
      e.passD(i => if (i == k) 0x7abcdL else e.ppnA(i))
      // the root descriptor too
      e.swStore(ROOT_A + ((VPN0 >> 13) & 0x7f) * 4); e.pflusha()
      val (f1, _, _, _) = e.snap
      e.passD(i => if (i == k) 0x7abcdL else e.ppnA(i))
      assert(e.snap._1 - f1 == N, "a root-descriptor store must force re-walks")
    }
  }

  test("survive: U first use -- the walker's own U store is snooped; results stay exact", VerilatorTest) {
    compiledSurvive.doSim { dut =>
      val e = new Env(dut); e.init()
      e.passD(i => e.ppnA(i)); e.passD(i => e.ppnA(i))
      val v = VPN0 + 5
      e.setLeaf(PAGT_A, v, 0x77777L, 0x01)             // (software would PFLUSH; snoop it)
      e.swStore(e.leafAddr(PAGT_A, v)); e.pflusha()
      assert(e.dAccess(v)._1 == 0x77777L)
      var g = 0
      while (e.peekLow(e.leafAddr(PAGT_A, v)) != 0x09 && g < 200) { e.cd.waitSampling(); g += 1 }
      assert(e.peekLow(e.leafAddr(PAGT_A, v)) == 0x09, "U must be set")
      e.evictD(); e.evictD()
      val (f0, h0, _, _) = e.snap
      assert(e.dAccess(v)._1 == 0x77777L)
      e.evictD()
      val (f1, h1, _, _) = e.snap
      assert(e.dAccess(v)._1 == 0x77777L)
      assert(e.snap._2 == h1 + 1, "the page is cached once its U is set in memory")
      // M first write: must walk and set M even though the read entry is cached
      e.evictD()
      val (f2, h2, _, _) = e.snap
      e.dAccess(v, write = true)
      assert(e.snap._1 == f2 + 1 && e.snap._2 == h2, "write to an M=0 entry walks")
      g = 0
      while (e.peekLow(e.leafAddr(PAGT_A, v)) != 0x19 && g < 200) { e.cd.waitSampling(); g += 1 }
      assert(e.peekLow(e.leafAddr(PAGT_A, v)) == 0x19, "M must be set")
    }
  }

  test("survive: a 4-granule filter overflows and stays exact", VerilatorTest) {
    compiledSurvive4.doSim { dut =>
      val e = new Env(dut); e.init()
      for (_ <- 0 until 3) { e.passD(i => e.ppnA(i)); e.evictD(); e.pflusha() }
      info(s"4 granules: full ${e.dFull}, L2 hits ${e.dL2}")
      e.setLeaf(PAGT_A, VPN0 + 3, 0x7bbbbL, 0x19)
      e.swStore(e.leafAddr(PAGT_A, VPN0 + 3)); e.pflusha()
      e.passD(i => if (i == 3) 0x7bbbbL else e.ppnA(i))
    }
  }
}
