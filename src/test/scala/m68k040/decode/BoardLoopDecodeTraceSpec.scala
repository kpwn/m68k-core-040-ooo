package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.services.GshareSecondaryLookupService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Replay exact board-read bytes at their original PCs through the real frontend.
  * This is a decoder trace, not a live hardware execution/physical-register trace.
  */
class BoardLoopDecodeTraceSpec extends AnyFunSuite {
  // Cold/not-taken prediction fixture: trace one sequential decode pass, not
  // execution or predictor training. Slot-1 admission still needs this service.
  class ColdPredictionPlugin extends FiberPlugin with GshareSecondaryLookupService {
    val logic = during build new Area {
      val index = UInt(11 bits); index := 0
      val taken = Bool(); taken := False
    }
    override def secondaryPhtIndex: UInt = logic.index
    override def secondaryPhtTaken: Bool = logic.taken
  }
  class Dut(combined: Boolean) extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin(trainSlot1Conditional = combined,
      deferTakenSlot1Conditional = combined)
    val dec = new DecodeStage(allowSlot1Prediction = combined, fuseLongMoveLoads = combined)
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, ic,
      new ColdPredictionPlugin, fa, dec, sink)) }
  }

  test("board Dhrystone byte-copy loop emits an auditable cracked uop sequence", VerilatorTest) {
    for (combined <- Seq(false, true)) {
      SimConfig.withVerilator.compile(new Dut(combined)).doSim(s"board_loop_$combined", 1) { dut =>
        val cd = dut.clockDomain; cd.forkStimulus(10)
        val base = 0x03be19c2L
        val pcs = Seq(base, base + 4, base + 8, base + 10)
        IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base,
          Seq(0x206e, 0x000c, 0x52ae, 0x000c, 0x12d0, 0x66f4) ++ Seq.fill(32)(0x4e71))
        dut.sink.logic.uopsOut.ready #= false
        dut.fa.logic.resume.valid #= false
        dut.fa.logic.redirect.valid #= false
        cd.waitSampling(3)
        dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
        cd.waitSampling(); dut.fa.logic.redirect.valid #= false
        dut.sink.logic.uopsOut.ready #= true
        val seen = scala.collection.mutable.ArrayBuffer.empty[(Long, Boolean, Boolean)]
        var guard = 0
        while (!seen.exists(x => x._1 == pcs.last && x._3) && guard < 500) {
          sleep(1)
          val out = dut.sink.logic.uopsOut
          if (out.valid.toBoolean && out.ready.toBoolean) {
            for (lane <- 0 until (if (dut.sink.logic.u1v.toBoolean) 2 else 1)) {
              val u = out.payload(lane)
              val pc = u.pc.toLong
              if (pcs.contains(pc)) {
                def reg(valid: Boolean, id: Int): String =
                  if (!valid) "-" else if (id < 8) s"D$id" else if (id < 16) s"A${id - 8}" else s"T${id - 16}"
                seen += ((pc, u.firstOfInstr.toBoolean, u.lastOfInstr.toBoolean))
                println(f"BOARD_UOP combined=$combined n=${seen.size}%d pc=$pc%08x " +
                  s"op=${u.op.toEnum} cluster=${u.cluster.toEnum} mem=${u.memOp.toEnum} size=${u.size.toEnum} " +
                  s"srcA=${reg(u.srcAValid.toBoolean, u.srcAReg.toInt)} " +
                  s"srcB=${reg(u.srcBValid.toBoolean, u.srcBReg.toInt)} " +
                  s"srcC=${reg(u.srcCValid.toBoolean, u.srcCReg.toInt)} " +
                  s"dst=${reg(u.dstValid.toBoolean, u.dstReg.toInt)} " +
                  f"useImm=${u.useImm.toBoolean} imm=${u.imm.toLong}%08x " +
                  s"eaAuto=${u.eaAuto.toEnum} eaDelta=${u.eaDelta.toInt} " +
                  s"readNZVC=${u.readsNzvc.toBoolean} writeNZVC=${u.writesNzvc.toBoolean} " +
                  s"readX=${u.readsX.toBoolean} writeX=${u.writesX.toBoolean} " +
                  s"first=${u.firstOfInstr.toBoolean} last=${u.lastOfInstr.toBoolean}")
                assert(!u.faulted.toBoolean && !u.unimplemented.toBoolean)
              }
            }
          }
          cd.waitSampling(); guard += 1
        }
        assert(seen.filter(_._2).map(_._1).toSeq == pcs, s"bad macro starts: $seen")
        assert(seen.filter(_._3).map(_._1).toSeq == pcs, s"bad macro ends: $seen")
        println(s"BOARD_UOP_SUMMARY combined=$combined macros=${pcs.size} uops=${seen.size} " +
          pcs.map(pc => f"$pc%08x:${seen.count(_._1 == pc)}").mkString(" "))
      }
    }
  }
}
