package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** CPU_DECODE_PAIR_3P1: a 3-uop slot0 crack pairs with a 1-uop slot1 (lane 3 = slot1's
  * uop 0) instead of deferring slot1 to the stash.
  *
  * The pairing changes only WHEN uops enter the MicroOpQueue, never WHICH uops or in what
  * order. So the property is: the widened uop stream on the rename-facing port (every
  * field, incl. the side-table-expanded 32-bit immediate, first/last-of-instr, pc, the
  * branch-prediction record's tag) is bit-identical between the flag OFF and ON decoders,
  *   - with no flush, under periodic backpressure; and
  *   - after a pipeFlush + redirect injected at EVERY cycle of a window that covers the
  *     3+1 pairs (flush on the pair's fed cycle, on its pushReg cycle, while it sits in the
  *     queue): the post-redirect stream must equal the no-flush reference prefix.
  * The program mixes the 3+1 shapes: postinc loads followed by MOVEQ / a Bcc (branch in
  * lane 3) / a wide #imm32 MOVE (wide group-bank immediate in lane 3) / an RMW, and ends
  * on ILLEGAL (faulted uop in lane 3), plus 3+2 and 3+3 controls that must still defer.
  * Non-vacuity: ON must push count==4 groups with a 3-uop head; OFF must never. */
class DecodePair3p1Spec extends AnyFunSuite {
  class ProbedDecode(pair: Boolean) extends DecodeStage(pair3p1 = pair) {
    val probe = during build new Area {
      val d = logic
      val flush = in Bool()
      d.pipeFlush := flush
      // A 3+1 push: the produced group holds 4 uops and the head was a 3-uop slot0.
      val pair31 = out(Bool())
      pair31 := d.pushProduced.fire && d.normalPushSel && !d.stashValid && d.slot0Is3 &&
                d.pushProduced.payload.count === 4
      val output = Vec(Bits(logic.uopsOut.payload(0).getBitsWidth bits), 2)
      for (i <- 0 until 2) {
        val u = cloneOf(logic.uopsOut.payload(i))
        u := logic.uopsOut.payload(i)
        u.valid.allowOverride
        u.valid := True
        output(i) := u.asBits
      }
      output.simPublic()
    }
  }

  class Dut(pair: Boolean) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val ic = new IcachePlugin
    val fa = new FetchAlignPlugin
    val dec = new ProbedDecode(pair)
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()),
      new IdentityTranslationPlugin, ic, fa, dec, sink)) }
  }

  // Straight-line body (decode does not execute it). Each line is one pairing shape.
  private val body: Seq[Int] = Seq(
    0x2018, 0x7201,                 // move.l (a0)+,d0 [3] ; moveq #1,d1 [1]       -> 3+1
    0x1019, 0xB019, 0x6602,         // move.b (a1)+,d0 [3] ; cmp.b (a1)+,d0 [3] ; bne.s  (3+3, then 3+1 w/ branch)
    0x4E71, 0x4E71,
    0x2018, 0x263C, 0x1234, 0x5678, // move.l (a0)+,d0 ; move.l #$12345678,d3 [1, wide imm in lane 3]
    0x5290, 0x7605,                 // addq.l #1,(a0) [3, RMW] ; moveq #5,d3          -> 3+1
    0x2018, 0xD290,                 // move.l (a0)+,d0 ; add.l (a0),d1 [2]           -> 3+2, must defer
    0xB298, 0x7403,                 // cmp.l (a0)+,d1 [3] ; moveq #3,d2               -> 3+1
    0x4A98, 0x5281,                 // tst.l (a0)+ [3] ; addq.l #1,d1                 -> 3+1
    0x4E71, 0x4E71, 0x4E71)
  // The frontend stops at a trapping opword, so the faulted-lane-3 shape closes the image:
  // add.l (a0)+,d1 [3] ; ILLEGAL [faulted uop in lane 3]; the stream ends there.
  private val tail: Seq[Int] = Seq(0xD298, 0x4AFC)
  private val copies = 8
  private val base = 0x8000L

  private def runOne(pair: Boolean): (Seq[BigInt], Seq[Seq[BigInt]], Int, Int) = {
    val image = Array.fill(0x2000)(0x4e71)
    for (c <- 0 until copies; (w, i) <- body.zipWithIndex) image(c * body.length + i) = w
    for ((w, i) <- tail.zipWithIndex) image(copies * body.length + i) = w
    var refOut: Seq[BigInt] = null
    val flushRuns = ArrayBuffer[Seq[BigInt]]()
    var pairs, ready0 = 0
    SimConfig.withVerilator.compile(new Dut(pair)).doSim(s"pair3p1_$pair", 3) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val p = dut.dec.probe
      val out = dut.sink.logic.uopsOut
      p.flush #= false
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= false
      out.ready #= false
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, image.toSeq)
      cd.waitSampling(5)

      /** Flush, redirect to `base`, then collect `n` uops; if `flushAt >= 0`, pulse a
        * second flush+redirect at that cycle and restart collection from scratch. */
      def run(flushAt: Int): Seq[BigInt] = {
        p.flush #= true; cd.waitSampling()
        dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
        cd.waitSampling(); dut.fa.logic.redirect.valid #= false; p.flush #= false
        val got = ArrayBuffer[BigInt]()
        var cycle = 0
        var idle = 0
        while ((cycle <= flushAt + 1 || idle < 300) && cycle < 8000) {
          // Flush for two cycles; the redirect rides the second (as in the full core,
          // where the frontend flush and the redirect arrive together).
          val flushing = cycle == flushAt || cycle == flushAt + 1
          out.ready #= (cycle % 23 >= 6)   // periodic backpressure fills the queue
          p.flush #= flushing
          dut.fa.logic.redirect.valid #= (cycle == flushAt + 1)
          dut.fa.logic.redirect.payload #= base
          sleep(1)
          if (p.pair31.toBoolean) pairs += 1
          if (flushing) {
            got.clear()   // everything before is squashed
          } else {
            idle += 1
            if (out.valid.toBoolean && out.ready.toBoolean) {
              idle = 0
              got += p.output(0).toBigInt
              if (dut.sink.logic.u1v.toBoolean) got += p.output(1).toBigInt
            }
          }
          cd.waitSampling(); cycle += 1
        }
        p.flush #= false; dut.fa.logic.redirect.valid #= false
        assert(cycle < 8000, s"pair=$pair flushAt=$flushAt: stream never went idle")
        got.toSeq
      }
      refOut = run(-1)
      ready0 = pairs
      for (t <- 2 until 60) flushRuns += run(t)
    }
    (refOut, flushRuns.toSeq, ready0, pairs)
  }

  test("3+1 pairing: uop stream identical to the unpaired decoder, incl. flush mid-pair", VerilatorTest) {
    val (offRef, offFl, offPairs0, offPairs) = runOne(pair = false)
    val (onRef, onFl, onPairs0, onPairs) = runOne(pair = true)
    assert(offRef.size > copies * 30, s"stream too short: ${offRef.size} uops")
    assert(offPairs == 0, s"flag OFF produced $offPairs 3+1 pushes")
    assert(onPairs0 > 0, "vacuous: the ON decoder never pushed a 3+1 group")
    assert(onRef == offRef, {
      val i = onRef.zip(offRef).indexWhere { case (a, b) => a != b }
      s"ON uop stream diverges from OFF at uop $i"
    })
    for ((s, t) <- offFl.zipWithIndex) assert(s == offRef, s"OFF: flush at ${t + 2} changed the restarted stream")
    for ((s, t) <- onFl.zipWithIndex) assert(s == offRef, s"ON: flush at ${t + 2} changed the restarted stream")
    info(s"uops=${offRef.size}; 3+1 pushes: ON reference run=$onPairs0, ON total incl. flush runs=$onPairs; OFF=$offPairs")
  }
}
