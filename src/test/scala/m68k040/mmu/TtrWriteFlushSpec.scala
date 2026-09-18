package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.{DTranslationService, MmuControlService}
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** A RESPONSE CAPTURED UNDER ONE TTR CONFIGURATION MUST NOT BE DELIVERED AFTER IT CHANGES.
  *
  * FOURTH member of the "walk/response latched some MMU configuration and the consumer
  * re-derives it live" family, after TCR.P re-keying, the ITLB walk-flush poison, and
  * the root-change-at-launch hole.
  *
  * `DtlbPlugin`'s response mux selects its arm inside `when(_req.fire)` --
  * `when(ttHit) ... elsewhen(!mmuEnable) ... elsewhen(tlbHit)` -- and REGISTERS the
  * payload. The arm therefore records the `mmuEnable` of the CAPTURE cycle, while the
  * consumer receives the payload one or more cycles later. A MOVEC clearing TC.E in
  * between leaves a TRANSLATED PA being delivered to an access that should by then be
  * identity-mapped -- and that access is necessarily YOUNGER than the MOVEC, because
  * MOVEC retires at the ROB head and every older access has already completed.
  *
  * `pageSizeRekey` already killed a pending response on a TC.P change (it feeds
  * `atcFlush`, whose flush block clears `rspValid`). An E-only change did not. Masked
  * incidentally by the sysOp redirect squashing younger ops -- the same unasserted
  * chain that hid the root-change hole.
  *
  * The test holds a response un-consumed (the probe keeps `rsp.ready` low while
  * `reqIn.valid` is high), clears TC.E through the REAL commit-time `setEnable` port,
  * and then asks what the access is told. Correct is identity (PA = VA); the defect
  * answers with the translated PPN the ATC held while paging was on. */
class TtrWriteFlushSpec extends AnyFunSuite {

  class Probe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val mctrl = host[MmuControlService]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val ttrWriteValid = in Bool ()
      val ttrWriteVal   = in UInt (32 bits)
      val requestIssued = RegInit(False)
      xlate.req.valid              := reqIn.valid && !requestIssued
      xlate.req.payload.vpn        := reqIn.vpn
      xlate.req.payload.supervisor := reqIn.supervisor
      xlate.req.payload.write      := reqIn.write
      xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
      // Response is CONSUMED only while the request is withdrawn -- that is what lets
      // the test park a captured response and then change TC.E under it.
      xlate.rsp.ready   := !reqIn.valid
      rspOut.ready      := xlate.rsp.valid
      rspOut.ppn        := xlate.rsp.payload.ppn
      rspOut.cacheMode  := xlate.rsp.payload.cacheMode
      rspOut.fault      := xlate.rsp.payload.fault
      when(xlate.req.fire) { requestIssued := True }
      when(!reqIn.valid)   { requestIssued := False }
      // The real commit-time MOVEC-to-DTT0 write port.
      when(ttrWriteValid) {
        mctrl.setDtt0.valid   := True
        mctrl.setDtt0.payload := ttrWriteVal
      }
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new Probe()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  test("a response captured under one TTR configuration must not be delivered after it changes", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0
      p.reqIn.write #= false; p.reqIn.supervisor #= true
      p.ttrWriteValid #= false; p.ttrWriteVal #= 0
      cd.waitSampling(4)

      val vpn  = 0x02003L
      val ppnA = 0x33333L
      pokeWord(mem, ROOT + ((vpn >> 13) & 0x7f) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + ((vpn >> 6) & 0x7f) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PAGT + (vpn & 0x3f) * 4, ((ppnA << 12) & 0xfffff000L) | 0x19L)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.urp #= ROOT
      // DTT0 covers 0x02xxxxxx transparently and NON-CACHEABLE (CM[1] = bit 6).
      // base=0x02, mask=0x00, E=1, S=1x, CM=10 -> 0x0200C040.
      dut.ctrl.logic.dtt0 #= 0x0200C040L
      cd.waitSampling(2)

      // ── prime: one full walk so the page is resident and a later lookup HITS ──
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      var g = 0
      while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      sleep(1)
      assert(p.rspOut.ppn.toLong == vpn, f"the TTR hit is identity: got 0x${p.rspOut.ppn.toLong}%05x")
      val primedMode = p.rspOut.cacheMode.toEnum.toString
      assert(primedMode == "INHIBITED", s"DTT0 says non-cacheable; got $primedMode")
      p.reqIn.valid #= false
      cd.waitSampling(4)

      // ── capture a response under TC.E=1 and PARK it (request held, ready low) ──
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      cd.waitSampling(3)

      // ── TC.E clears through the real MOVEC port, while that response is parked ──
      p.ttrWriteVal   #= 0L          // DTT0 disabled (E=0): the region is no longer transparent
      p.ttrWriteValid #= true
      cd.waitSampling()
      p.ttrWriteValid #= false
      cd.waitSampling(2)
      assert(dut.ctrl.logic.dtt0.toLong == 0L, "the MOVEC write port must have cleared DTT0")
      cd.waitSampling(4)

      // ── THE MEASUREMENT. The response is still PARKED (request held, ready low),
      // so `rspOut` shows exactly what the DTLB is offering the requester right now.
      // Reading it here is the whole point: the previous version of this test dropped
      // the request first, which RETIRED the parked response and then observed a FRESH
      // one computed under the new TC.E -- so it passed with the mechanism removed and
      // was testing nothing. The negative control caught that.
      val stillOffered = p.rspOut.ready.toBoolean
      val offeredMode  = p.rspOut.cacheMode.toEnum.toString
      info(s"after DTT0 was disabled with a response parked: offered=$stillOffered cacheMode=$offeredMode")
      assert(!(stillOffered && offeredMode == "INHIBITED"),
        s"STALE TTR VERDICT OFFERED: the DTLB is still presenting a transparent, " +
        s"$offeredMode response for a region DTT0 no longer covers. The arm and the " +
        s"cache mode were chosen at capture under the OLD DTT0 and never re-derived -- " +
        s"the shape that produced the DAFB burst-refill-into-AXI-lite bug.")

      // ...and once the access re-issues, with no TTR covering it, it must reach the
      // PAGE TABLES and come back with the walked PPN.
      p.reqIn.valid #= false
      cd.waitSampling(3)
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      g = 0
      while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      sleep(1)
      val ppn = p.rspOut.ppn.toLong
      assert(ppn == ppnA,
        f"with DTT0 disabled the access must WALK: vpn 0x$vpn%05x got ppn 0x$ppn%05x, expected 0x$ppnA%05x")
    }
  }
}
