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

/** A WALK WHOSE ROOT POINTER CHANGED UNDER IT MUST NOT INSTALL ITS RESULT.
  *
  * `walker.io.req.rootPtr` is read LIVE at walk LAUNCH and latched into the walker
  * there, so a walk carries the root it started under. A MOVEC to SRP/URP while that
  * walk is still reading descriptors leaves it completing against a tree that is no
  * longer installed -- and, before this fix, installing the result in the ATC.
  *
  * It was masked INCIDENTALLY -- sysOp retire -> redirect -> `doFlush` -> `umFlush` ->
  * `walkUmPoison` -> gates `fillValid` -- with nothing naming or asserting that chain.
  * THIS TEST DELIBERATELY BREAKS THE MASK: it writes SRP through the real commit-time
  * `MmuControlService.setSrp` port and pulses NO flush of any kind (no `umFlush`, no
  * PFLUSHA), which is exactly what an incidental mask cannot survive. On the unfixed
  * RTL the pre-change translation is installed and answers the next access.
  *
  * Third of a family: TCR.P re-keying, the ITLB walk-flush poison, and this. The shared
  * shape is "the walk latches configuration at launch and a consumer re-derives it
  * live". */
class RootChangeMidWalkSpec extends AnyFunSuite {

  class DProbe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val mctrl = host[MmuControlService]
      val reqIn = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val srpWriteValid = in Bool ()
      val srpWriteVal   = in UInt (32 bits)
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
      // The REAL commit-time MOVEC-to-SRP write port. No flush of any kind is driven:
      // `dtlb.flushAll` and `dtlb.umFlush` keep their idle defaults for this whole test.
      when(srpWriteValid) {
        mctrl.setSrp.valid   := True
        mctrl.setSrp.payload := srpWriteVal
      }
      val walkDone = out Bool (); walkDone := dtlb.logic.walker.io.done
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new DProbe()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT_A = 0x10000L; private val PTRT_A = 0x11000L; private val PAGT_A = 0x12000L
  private val ROOT_B = 0x20000L; private val PTRT_B = 0x21000L; private val PAGT_B = 0x22000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def buildTree(mem: DcacheClientMemAgent, root: Long, ptrt: Long, pagt: Long,
                        vpn: Long, ppn: Long): Unit = {
    pokeWord(mem, root + ((vpn >> 13) & 0x7f) * 4, (ptrt & 0xfffffff0L) | 0x3L)
    pokeWord(mem, ptrt + ((vpn >> 6) & 0x7f) * 4, (pagt & 0xfffffff0L) | 0x3L)
    pokeWord(mem, pagt + (vpn & 0x3f) * 4, ((ppn << 12) & 0xfffff000L) | 0x19L)
  }

  test("D side: SRP written mid-walk, with NO flush, must not install the pre-change translation",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= false; p.reqIn.vpn #= 0
      p.reqIn.write #= false; p.reqIn.supervisor #= true
      p.srpWriteValid #= false; p.srpWriteVal #= 0
      cd.waitSampling(4)

      val vpn  = 0x02003L
      val ppnA = 0x33333L
      val ppnB = 0x44444L
      buildTree(mem, ROOT_A, PTRT_A, PAGT_A, vpn, ppnA)
      buildTree(mem, ROOT_B, PTRT_B, PAGT_B, vpn, ppnB)

      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT_A
      dut.ctrl.logic.urp #= ROOT_A
      cd.waitSampling(2)

      var ar = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) ar += 1 } }

      // launch a walk under ROOT_A
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      var g = 0
      while (ar < 1 && g < 300) { cd.waitSampling(); g += 1 }
      assert(ar >= 1, "the walk must have started before SRP changes")

      // ── the root changes MID-WALK, through the real MOVEC port, with NO flush ──
      p.srpWriteVal   #= ROOT_B
      p.srpWriteValid #= true
      cd.waitSampling()
      p.srpWriteValid #= false
      // Settle before reading the register back: the write lands ON the edge
      // `waitSampling` returns from, so an immediate read races the delta.
      cd.waitSampling(2)
      assert(dut.ctrl.logic.srp.toLong == ROOT_B, "the MOVEC write port must have changed SRP")

      g = 0
      while (!p.walkDone.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      cd.waitSampling(4)
      p.reqIn.valid #= false
      cd.waitSampling(6)
      val arAfterWalk = ar

      // ── the access re-issues; it must be answered from the NEW tree ──
      p.reqIn.vpn #= vpn
      p.reqIn.valid #= true
      cd.waitSampling()
      g = 0
      while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
      sleep(1)
      val ppn   = p.rspOut.ppn.toLong
      val fault = p.rspOut.fault.toBoolean
      val reWalk = ar - arAfterWalk
      info(f"post-SRP-change request -> ppn 0x$ppn%05x (new tree 0x$ppnB%05x, " +
           f"PRE-CHANGE tree 0x$ppnA%05x), fault=$fault, re-walk reads=$reWalk")
      assert(!fault, "no fault either way")
      assert(ppn == ppnB,
        f"STALE ROOT SURVIVED: vpn 0x$vpn%05x served ppn 0x$ppn%05x from the tree SRP " +
        f"pointed at when the walk LAUNCHED; SRP now names a tree that maps it to " +
        f"0x$ppnB%05x (re-walk reads = $reWalk)")
      assert(reWalk > 0, "the post-change access must RE-WALK, not hit a surviving entry")
    }
  }
}
