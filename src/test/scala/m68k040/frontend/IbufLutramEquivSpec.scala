package m68k040.frontend

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.util.Random

/** CPU_IBUF_LUTRAM: the four-bank LUTRAM ring must drive EXACTLY the flop ring's outputs
  * (head words, gated and raw predecode, avail, cnt, push ready) every cycle, under random
  * push / shift / flush traffic, for both the plain and the CPU_FE_RETIME shift interface.
  * The ungated `headPredRaw` reads stale rows too, so both rings are first filled end to
  * end (the flop ring has no payload reset either) and compared on every cycle after. */
class IbufLutramEquivSpec extends AnyFunSuite {
  class Dut(retime: Boolean) extends Component {
    val a = new InstructionBuffer(retime, lutram = false)
    val b = new InstructionBuffer(retime, lutram = true)
    val pushValid = in Bool()
    val pushWords = in Vec(Bits(16 bits), 4)
    val pushLen   = in Vec(UInt(4 bits), 4)
    val pushAmb   = in Vec(Bool(), 4)
    val pushCx    = in Vec(Bool(), 4)
    val pushSize  = in Vec(UInt(2 bits), 4)
    val pushN     = in UInt(3 bits)
    val flush     = in Bool()
    val shEn      = in Bool()
    val shA       = in UInt(4 bits)
    val shB       = in UInt(4 bits)
    val shSelA    = in Bool()
    val cnt       = out UInt(5 bits)
    val ready     = out Bool()
    val mismatch  = out Bool()
    val rawMismatch = out Bool()
    val shAe = Mux(shA.resize(5) > a.io.cnt, a.io.cnt.resize(4), shA)
    val shBe = Mux(shB.resize(5) > a.io.cnt, a.io.cnt.resize(4), shB)
    for (ib <- Seq(a, b)) {
      ib.io.push.valid := pushValid
      for (j <- 0 until 4) {
        ib.io.push.payload.words(j) := pushWords(j)
        ib.io.push.payload.preds(j).lenWords := pushLen(j)
        ib.io.push.payload.preds(j).ambiguousLine := pushAmb(j)
        ib.io.push.payload.preds(j).size := pushSize(j).mux(
          0 -> m68k040.isa.Size.BYTE(), 1 -> m68k040.isa.Size.WORD(), default -> m68k040.isa.Size.LONG())
        ib.io.push.payload.preds(j).ctrlXfer := pushCx(j)
      }
      ib.io.push.payload.n := pushN
      ib.io.flush := flush
      ib.io.shift := Mux(shEn, Mux(shSelA, shAe, shBe), U(0, 4 bits))
      if (retime) {
        ib.io.shiftEn := shEn; ib.io.shiftA := shAe; ib.io.shiftB := shBe; ib.io.shiftSelA := shSelA
      }
    }
    cnt   := a.io.cnt
    ready := a.io.push.ready
    mismatch := a.io.cnt =/= b.io.cnt || a.io.avail =/= b.io.avail ||
                a.io.head.asBits =/= b.io.head.asBits || a.io.headPred.asBits =/= b.io.headPred.asBits ||
                a.io.push.ready =/= b.io.push.ready
    rawMismatch := (if (retime) a.io.headPredRaw.asBits =/= b.io.headPredRaw.asBits else False)
  }

  for (retime <- Seq(false, true)) test(s"CPU_IBUF_LUTRAM ring == flop ring (retime=$retime)") {
    M68kSim().withVerilator.compile(new Dut(retime)).doSim(seed = 23) { dut =>
      val rnd = new Random(23 + (if (retime) 1 else 0))
      dut.clockDomain.forkStimulus(10)
      def drivePush(v: Boolean, n: Int): Unit = {
        dut.pushValid #= v; dut.pushN #= n
        for (j <- 0 until 4) {
          dut.pushWords(j) #= rnd.nextInt(65536); dut.pushLen(j) #= rnd.nextInt(16)
          dut.pushAmb(j) #= rnd.nextBoolean(); dut.pushCx(j) #= rnd.nextBoolean()
          dut.pushSize(j) #= rnd.nextInt(3)
        }
      }
      dut.flush #= true; dut.shEn #= false; dut.shA #= 0; dut.shB #= 0; dut.shSelA #= false
      drivePush(false, 0)
      dut.clockDomain.waitSampling(2)
      // Warm-up: write every physical row of both rings once (5 x 4 words, no shift).
      dut.flush #= false
      for (_ <- 0 until 5) { drivePush(true, 4); dut.clockDomain.waitSampling() }
      dut.pushValid #= false; dut.flush #= true; dut.clockDomain.waitSampling()
      var shifted = 0L; var pushed = 0L; var deep = 0L
      for (n <- 0 until 200000) {
        val c = dut.cnt.toInt
        dut.flush #= rnd.nextInt(300) == 0
        drivePush(dut.ready.toBoolean && rnd.nextInt(3) != 0, rnd.nextInt(5))
        val lim = scala.math.min(c, if (rnd.nextBoolean()) 10 else 15)
        dut.shA #= rnd.nextInt(lim + 1); dut.shB #= rnd.nextInt(lim + 1); dut.shSelA #= rnd.nextBoolean()
        dut.shEn #= rnd.nextInt(3) != 0
        sleep(1)
        assert(!dut.mismatch.toBoolean, s"LUTRAM IBuf diverged at cycle $n (cnt=$c)")
        assert(!dut.rawMismatch.toBoolean, s"LUTRAM IBuf raw predecode diverged at cycle $n (cnt=$c)")
        if (dut.shEn.toBoolean) shifted += 1
        if (dut.pushValid.toBoolean) pushed += 1
        if (c >= 12) deep += 1
        dut.clockDomain.waitSampling()
      }
      println(s"[IbufLutramEquiv] retime=$retime shifted=$shifted pushed=$pushed deep=$deep")
      assert(shifted > 50000 && pushed > 50000 && deep > 5000)
    }
  }
}
