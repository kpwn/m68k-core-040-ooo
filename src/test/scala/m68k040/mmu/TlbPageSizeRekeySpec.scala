package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** TCR.P RE-KEYING: a resident ATC entry filled under one page size becomes a
  * clean ONE-HOT match for a DIFFERENT virtual page after TCR.P changes.
  *
  * `Dtlb/ItlbPlugin.tlbKey` derives the ATC array key from the LIVE `TCR.P`:
  *
  *     def tlbKeyOf(vpn: UInt, p8K: Bool): UInt = Mux(p8K, vpn(19 downto 1).resize(20), vpn)
  *     def tlbKey(vpn: UInt): UInt = tlbKeyOf(vpn, is8K)       // is8K = ctrl.pageSize8K
  *     tlb.io.lookupVpn := tlbKey(_req.payload.vpn)            // LIVE TCR.P
  *     tlb.io.fillVpn   := tlbKeyOf(walkVpn, walkIs8K)         // TCR.P AT MISS TIME
  *
  * The key stored in the array therefore encodes the page size the entry was
  * filled under, but nothing in the tag records WHICH page size that was. A
  * TCR.P change silently re-interprets every resident key:
  *
  *   4KB epoch: VA 0x00402000 (vpn 0x00402) fills key 0x00402.
  *   TCR.P := 1 (8KB), no PFLUSHA.
  *   8KB epoch: VA 0x00804000 (vpn 0x00804) looks up key 0x00804 >> 1 = 0x00402.
  *
  * -> a ONE-HOT hit on an entry describing a page at HALF this address, returning
  * its PPN with no fault. Because the hit is one-hot, the `dbgHitCount`/multi-hot
  * fail-safe cannot see it; the access simply reads an unrelated physical page.
  *
  * The MC68040 UM requires software to PFLUSH after changing translation control,
  * so this is a "software omitted the flush" case -- but the hardware must not
  * answer with a confidently wrong translation, and the re-keying makes the
  * aliasing VA*2 rather than the same 8KB region real silicon's masked-A12
  * comparison would give.
  *
  * Both directions are covered (4K entry seen by an 8K lookup, and 8K entry seen
  * by a 4K lookup). */
class TlbPageSizeRekeySpec extends AnyFunSuite {

  /** Same shape as DtlbSpec's probe: drive DTranslationService, expose the answer. */
  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      // A commit-time MOVEC-to-TC write port, exactly the shape ExceptionUnit drives
      // (`mmuCtrl.setPageSize.valid/payload`, allowOverride). Poking the `pageSize8K`
      // REGISTER directly would change the key without ever telling the MMU that TC
      // was written, which is not something the real core can do.
      val mctrl = host[m68k040.services.MmuControlService]
      val tcWriteValid = in Bool()
      val tcWriteP     = in Bool()
      when(tcWriteValid) {
        mctrl.setPageSize.valid   := True
        mctrl.setPageSize.payload := tcWriteP
      }
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
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT  = 0x10000L
  private val PTRT  = 0x11000L
  private val PAGT0 = 0x12000L   // page table reached by the 4K walk
  private val PAGT1 = 0x13000L   // page table reached by the 8K walk

  // Big-endian descriptor poke (TableWalker.selectWord convention), as in DtlbSpec.
  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  // Descriptor index slices, matching TableWalker: root = vpn[19:13],
  // pointer = vpn[12:6], page = vpn[5:0] (4K) / vpn[5:1] (8K).
  private def rootIdx(vpn: Long): Int = ((vpn >> 13) & 0x7f).toInt
  private def ptrIdx(vpn: Long): Int  = ((vpn >> 6) & 0x7f).toInt
  private def pageIdx4k(vpn: Long): Int = (vpn & 0x3f).toInt
  private def pageIdx8k(vpn: Long): Int = ((vpn >> 1) & 0x1f).toInt

  // resident (bits[1:0]=01) + U (bit3) + M (bit4) so no walk queues a deferred U/M
  // descriptor writeback (this DUT has no commit port to drain one).
  private def pageDesc(ppn: Long): Long = ((ppn << 12) & 0xfffff000L) | 0x19L

  private def lookup(dut: Dut, cd: ClockDomain, vpn: Long): (Boolean, Long, Boolean) = {
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpn
    dut.probe.logic.reqIn.write #= false
    dut.probe.logic.reqIn.supervisor #= false
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
    sleep(1)
    val r = (dut.probe.logic.rspOut.ready.toBoolean,
             dut.probe.logic.rspOut.ppn.toLong,
             dut.probe.logic.rspOut.fault.toBoolean)
    dut.probe.logic.reqIn.valid #= false
    cd.waitSampling(2)
    r
  }

  /** @param startIn8K page size the FIRST (priming) access runs under.
    *  vpnLo fills under 4K keying, vpnHi = 2*vpnLo collides with it under 8K keying. */
  private def rekeyBody(startIn8K: Boolean): Unit = {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      dut.probe.logic.reqIn.valid #= false
      dut.probe.logic.reqIn.vpn #= 0
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      dut.probe.logic.tcWriteValid #= false
      dut.probe.logic.tcWriteP #= false
      cd.waitSampling(4)

      val vpnLo  = 0x00402L            // VA 0x00402000, the 4K-keyed page
      val vpnHi  = 0x00804L            // VA 0x00804000; 8K key = 0x00804>>1 = 0x00402
      val ppnLo  = 0x12340L
      val ppnHi  = 0x5678AL
      assert((vpnHi >> 1) == vpnLo, "the two VPNs must collide under 8K keying")

      // One root, one pointer table; the 4K and 8K walks land on DIFFERENT pointer
      // entries (vpn[12:6] = 0x10 vs 0x20), so each gets its own page table.
      pokeWord(mem, ROOT + rootIdx(vpnLo) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ptrIdx(vpnLo) * 4, (PAGT0 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ptrIdx(vpnHi) * 4, (PAGT1 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PAGT0 + pageIdx4k(vpnLo) * 4, pageDesc(ppnLo))
      pokeWord(mem, PAGT1 + pageIdx8k(vpnHi) * 4, pageDesc(ppnHi))

      dut.ctrl.logic.mmuEnable  #= true
      dut.ctrl.logic.pageSize8K #= startIn8K
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)

      val (primeVpn, primePpn, victimVpn, victimPpn) =
        if (startIn8K) (vpnHi, ppnHi, vpnLo, ppnLo) else (vpnLo, ppnLo, vpnHi, ppnHi)

      // ---- epoch 1: cold walk fills the ATC under the starting page size ----
      val (r1, p1, f1) = lookup(dut, cd, primeVpn)
      assert(r1 && !f1, s"priming walk must resolve without fault (ready=$r1 fault=$f1)")
      assert(p1 == primePpn, f"priming walk ppn: got 0x$p1%05x want 0x$primePpn%05x")

      // ---- TCR.P flips (a MOVEC to TC), software omits the PFLUSHA ----
      dut.probe.logic.tcWriteP     #= !startIn8K
      dut.probe.logic.tcWriteValid #= true
      cd.waitSampling()
      dut.probe.logic.tcWriteValid #= false
      cd.waitSampling(4)
      assert(dut.ctrl.logic.pageSize8K.toBoolean == !startIn8K,
             "the MOVEC-to-TC write port must have flipped TCR.P")

      // ---- epoch 2: an architecturally DIFFERENT page, correctly mapped in the
      // tables, whose key now collides with the resident entry.
      var arCount = 0
      val counter = fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1 } }

      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= victimVpn
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling()
      var guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
      sleep(1)
      val ready = dut.probe.logic.rspOut.ready.toBoolean
      val ppn   = dut.probe.logic.rspOut.ppn.toLong
      val fault = dut.probe.logic.rspOut.fault.toBoolean
      val hitCount = dut.dtlb.logic.tlb.dbgHitCount.toInt
      counter.terminate()

      info(f"after TCR.P ${if (startIn8K) "1->0" else "0->1"}: vpn 0x$victimVpn%05x -> " +
           f"ppn 0x$ppn%05x (correct 0x$victimPpn%05x), fault=$fault, walker reads=$arCount, " +
           f"tlb hitVec popcount=$hitCount")

      assert(ready, "the post-TCR.P-change access must resolve")
      // The whole point: the bad answer is a CLEAN ONE-HOT hit, so the multi-hot
      // fail-safe is blind to it.
      if (arCount == 0) {
        assert(hitCount == 1,
          s"the stale answer is a one-hot hit (popcount=$hitCount) -- invisible to the multi-hot latch")
      }
      assert(!fault, "no fault is reported either way")
      assert(ppn == victimPpn,
        f"MISTRANSLATION: vpn 0x$victimVpn%05x served ppn 0x$ppn%05x " +
        f"(the page filled under the OTHER TCR.P), correct is 0x$victimPpn%05x; " +
        f"walker reads after the TCR.P change = $arCount")
    }
  }

  test("TCR.P 0->1 re-keys a resident 4K entry into a one-hot hit for VA*2", VerilatorTest) {
    rekeyBody(startIn8K = false)
  }

  test("TCR.P 1->0 re-keys a resident 8K entry into a one-hot hit for VA/2", VerilatorTest) {
    rekeyBody(startIn8K = true)
  }
}
