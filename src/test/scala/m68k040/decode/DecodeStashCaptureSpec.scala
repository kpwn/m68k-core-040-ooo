package m68k040.decode

import java.security.MessageDigest
import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{IcachePlugin, IcachePredecodeConfig, IcacheSim}
import m68k040.core.ParamPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Test-only subclass: observes its own decode state, not a sibling plugin's
  * internals. The reference reproduces the five pre-change write qualifiers. */
class DecodeStashCaptureSpec extends AnyFunSuite {
  class CheckedDecode extends DecodeStage {
    val check = during build new Area {
      val d = logic
      val ordinary = !d.slot1IsMovem && !d.slot1IsMovepEarly && !d.slot1IsFmovemx &&
        !d.slot1IsUcodeEarly && !d.slot1IsMemIndEarly && !d.slot1IsBfDynMemEarly && !d.slot1IsBfMemindMemEarly
      val writers = Bits(5 bits)
      writers(0) := !d.movemActive && !d.movemBegin && !d.ucBegin && !d.ucActive &&
        !d.movepActive && !d.movepBegin && !d.fmovemxActive && !d.fmovemxBegin &&
        !d.fpImmTable.stall && d.pushProduced.ready && !d.stashValid && ordinary &&
        d.deferSlot1 && d.fed.valid
      writers(1) := d.movemEnterSlot0 && d.fed.payload.slot1Valid && ordinary
      writers(2) := d.fmovemxEnterSlot0 && d.fed.payload.slot1Valid && ordinary
      writers(3) := d.ucEnterSlot0 && d.fed.payload.slot1Valid && ordinary
      writers(4) := d.movepEnterSlot0 && d.fed.payload.slot1Valid && ordinary
      val source = d.a1raw.uops.asBits ## d.a1raw.count.asBits ## d.a1raw.fpImmAlloc ## d.a1raw.fpWideImm
      val actual = d.stashUops.asBits ## d.stashCount.asBits ## d.stashFpPend ## d.stashFpVal
      val reference = Reg(Bits(source.getWidth bits))
      when(writers.orR) { reference := source }
      val mismatch = d.stashValid && actual =/= reference
      val occupiedWrite = writers.orR && d.stashValid
      // Optional exact-write reference, confined to the test subclass. Later
      // self assignments suppress the candidate's broad invalid capture.
      if (sys.env.get("STASH_EXACT_REFERENCE").contains("1")) {
        when(!writers.orR) {
          d.stashCount := d.stashCount
          d.stashUops := d.stashUops
          d.stashFpPend := d.stashFpPend
          d.stashFpVal := d.stashFpVal
        }
      }
      val valid = d.stashValid
      val fp = d.stashFpPend
      val flush = in Bool()
      // Override only this test instance, after the inherited build has finished.
      d.pipeFlush := flush
      val alloc = d.fpImmTable.allocFire
      val allocData = d.fpImmTable.allocIdx.asBits ## d.fpImmTable.headVal
      val output = Vec(Bits(d.queue.io.pop.payload(0).getBitsWidth bits), 2)
      for (i <- 0 until 2) {
        val observed = cloneOf(d.queue.io.pop.payload(i))
        observed := d.queue.io.pop.payload(i)
        // Packet.valid is a frontend don't-care; Stream.valid/pop1Valid owns
        // lane validity. RenameStage overwrites this redundant payload bit too.
        // Normalize exactly that field, not arbitrary inactive operand fields.
        observed.valid.allowOverride
        observed.valid := d.queue.io.pop.valid && (if (i == 0) True else d.queue.io.pop1Valid)
        output(i) := observed.asBits
      }
      Seq(writers, mismatch, occupiedWrite, valid, fp, actual, alloc, allocData).foreach(_.simPublic())
      output.foreach(_.simPublic())
    }
  }

  class Dut extends Component {
    val db = new Database
    val host = db on new PluginHost
    val ic = new IcachePlugin(predecodeWords = IcachePredecodeConfig.fromEnvironment)
    val fa = new FetchAlignPlugin
    val dec = new CheckedDecode
    val sink = new UopSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()),
      new IdentityTranslationPlugin, ic, fa, dec, sink)) }
  }

  private val programs = Seq(
    "normal" -> Seq(0x5290, 0x7001), // ADDQ.L #1,(A0): three uops, then MOVEQ
    "movem" -> Seq(0x4cd8, 0x0003, 0x7202),
    "fmovemx" -> Seq(0xf210, 0xd080, 0x7403),
    "ucode" -> Seq(0xc109, 0x7604), // ABCD -(A1),-(A0)
    "movep" -> Seq(0x0149, 0x0000, 0x7805),
    "fp-immediate" -> Seq(0x7006, 0xf23c, 0x5022, 0x8123))

  test("all five stash writers preserve valid payload under backpressure and coincident flush", VerilatorTest) {
    val image = Array.fill(0x4000)(0x4e71)
    programs.zipWithIndex.foreach { case ((_, body), p) =>
      for (copy <- 0 until 16; (word, i) <- body.zipWithIndex) image(p * 0x400 + copy * 16 + i) = word
    }
    SimConfig.withVerilator.compile(new Dut).doSim("stash_capture", 17) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val base = 0x8000L
      val c = dut.dec.check
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid #= false
      c.flush #= false
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, image.toSeq)
      cd.waitSampling(5)
      val counts = Array.fill(5)(0)
      var held, fpHeld, allocations, flushCapture, flushReplay = 0
      val digest = MessageDigest.getInstance("SHA-256")
      def hash(value: String): Unit = {
        digest.update((value + "\n").getBytes("UTF-8"))
        if (sys.env.get("STASH_TRACE").contains("1")) println("STASH_TRACE " + value)
      }
      for (mode <- 0 until 3; ((name, _), p) <- programs.zipWithIndex) {
        c.flush #= true
        cd.waitSampling()
        c.flush #= false
        dut.fa.logic.redirect.valid #= true
        dut.fa.logic.redirect.payload #= base + p * 0x800
        cd.waitSampling()
        dut.fa.logic.redirect.valid #= false
        var lastValid = false
        var lastPayload = BigInt(0)
        var flushed = false
        var pulses = 0
        for (cycle <- 0 until 600) {
          // Long blocked intervals fill the output queue, exercising held stashes.
          dut.sink.logic.uopsOut.ready #= (cycle % 61 >= 24)
          c.flush #= false
          sleep(1)
          assert(!c.occupiedWrite.toBoolean, s"$name/$mode writes an occupied stash")
          assert(!c.mismatch.toBoolean, s"$name/$mode valid payload differs from original writers")
          if (flushed) assert(!c.valid.toBoolean, s"$name flush failed to clear stash")
          val valid = c.valid.toBoolean
          val payload = c.actual.toBigInt
          if (valid && lastValid && !flushed) {
            assert(payload == lastPayload, s"$name held stash was overwritten")
            held += 1
          }
          if (valid && c.fp.toBoolean) fpHeld += 1
          val writers = c.writers.toInt
          for (i <- counts.indices if (writers & (1 << i)) != 0) counts(i) += 1
          val pulse = pulses < 3 && ((mode == 1 && writers != 0) || (mode == 2 && valid))
          if (pulse) {
            c.flush #= true
            pulses += 1
            if (mode == 1) flushCapture += 1 else flushReplay += 1
          }
          sleep(1)
          val out = dut.sink.logic.uopsOut
          hash(s"$mode/$p/$cycle/${out.valid.toBoolean}/${out.valid.toBoolean && dut.sink.logic.u1v.toBoolean}/$pulse")
          if (out.valid.toBoolean) {
            hash(c.output(0).toBigInt.toString(16))
            if (dut.sink.logic.u1v.toBoolean) hash(c.output(1).toBigInt.toString(16))
          }
          if (c.alloc.toBoolean) { allocations += 1; hash("fp=" + c.allocData.toBigInt.toString(16)) }
          lastValid = valid; lastPayload = payload; flushed = pulse
          cd.waitSampling()
        }
      }
      assert(counts.forall(_ > 0), s"vacuous writer coverage: ${counts.mkString(",")}")
      assert(held > 0 && fpHeld > 0 && allocations > 0, s"held=$held fp=$fpHeld alloc=$allocations")
      assert(flushCapture > 0 && flushReplay > 0)
      info(s"writers=${counts.mkString(",")} held=$held fp=$fpHeld alloc=$allocations captureFlush=$flushCapture replayFlush=$flushReplay")
      info("STASH_CYCLE_TRACE_SHA256=" + digest.digest().map(b => f"${b & 0xff}%02x").mkString)
    }
  }
}
