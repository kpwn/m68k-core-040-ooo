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

/** A RESIDENT ENTRY MUST NOT SURVIVE A ROOT-POINTER WRITE ("we should not depend on
  * pflusha", owner directive 2026-09-17).
  *
  * Distinct from `RootChangeMidWalkSpec`, which covers a walk ALREADY IN FLIGHT. This
  * is the plain case: the walk finished long ago, the entry is resident, and software
  * then writes SRP without flushing. On real 68040 the entry survives and software is
  * required to PFLUSH; under the owner's rule the hardware must not mistranslate when
  * software omits it.
  *
  * Reachable in CONFORMING software too: 7.0.1's MMU restore writes URP and SRP eight
  * instructions before its PFLUSHA, and every access in that window runs with the new
  * roots installed and the old entries still resident -- a window the program cannot
  * close, because the contract is "flush AFTER". */
class RootWriteFlushSpec extends AnyFunSuite {

  class Probe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
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
      // The real commit-time MOVEC-to-SRP port. NO flush is ever driven in this test:
      // `dtlb.flushAll` and `dtlb.umFlush` keep their idle defaults throughout.
      when(srpWriteValid) {
        mctrl.setSrp.valid   := True
        mctrl.setSrp.payload := srpWriteVal
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

  test("a RESIDENT entry must not answer after SRP is written with no flush", VerilatorTest) {
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

      // ── make the entry RESIDENT, and prove it by a second, walk-free lookup ──
      def lookup(): Long = {
        p.reqIn.vpn #= vpn
        p.reqIn.valid #= true
        var g = 0
        while (!p.rspOut.ready.toBoolean && g < 400) { cd.waitSampling(); g += 1 }
        sleep(1)
        val v = p.rspOut.ppn.toLong
        p.reqIn.valid #= false
        cd.waitSampling(4)
        v
      }
      assert(lookup() == ppnA, "priming walk must translate through the OLD tree")
      val afterPrime = ar
      assert(lookup() == ppnA, "second lookup must still translate")
      assert(ar == afterPrime, s"...and must be a RESIDENT HIT, no walk (ARs $afterPrime -> $ar)")

      // ── SRP is written. NO flush of any kind. The walk finished long ago. ──
      p.srpWriteVal   #= ROOT_B
      p.srpWriteValid #= true
      cd.waitSampling()
      p.srpWriteValid #= false
      cd.waitSampling(4)
      assert(dut.ctrl.logic.srp.toLong == ROOT_B, "the MOVEC write port must have changed SRP")
      val afterWrite = ar

      // ── the same VA again: the resident entry describes a tree SRP no longer names ──
      val ppn = lookup()
      val reWalk = ar - afterWrite
      info(f"after a bare SRP write (no flush): ppn 0x$ppn%05x " +
           f"(new tree 0x$ppnB%05x, OLD resident entry 0x$ppnA%05x), re-walk reads=$reWalk")
      assert(ppn == ppnB,
        f"RESIDENT ENTRY SURVIVED A ROOT WRITE: vpn 0x$vpn%05x served ppn 0x$ppn%05x from " +
        f"the tree SRP pointed at BEFORE the write; SRP now names a tree mapping it to " +
        f"0x$ppnB%05x. The hardware must not depend on software issuing a PFLUSH.")
      assert(reWalk > 0, "the post-write access must RE-WALK, not hit the surviving entry")
    }
  }
}
