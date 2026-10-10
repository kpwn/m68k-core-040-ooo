package m68k040.frontend

import m68k040.M68kSim
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** CPU_FE_RETIME directed equivalence (random stimulus, Verilator).
  *
  *  1. `Aligner.align(retime = true)` fed the UNGATED IBuf predecode (stale garbage
  *     beyond `count`) against the original aligner fed the GATED head, as the IBuf
  *     produces it. Every control output and every field of a valid packet must match.
  *  2. `InstructionBuffer(retime = true)` (head/count adders on the shift candidates)
  *     against the original, driven by the same push/flush/shift stream. */
class FeRetimeEquivSpec extends AnyFunSuite {
  val W = Aligner.WINDOW

  class AlignerEquivDut extends Component {
    val words    = in Vec(Bits(16 bits), W)
    val rawPreds = in Vec(ChunkPredecode(), W)
    val count    = in UInt(5 bits)
    val availIn  = in UInt(4 bits)
    val p0Live   = in(ChunkPredecode())
    val headPc   = in UInt(32 bits)
    val mismatch = out Bool()
    val slot1V   = out Bool()
    val slot0V   = out Bool()

    // Gate exactly as InstructionBuffer does for `head`/`headPred`.
    val gWords = Vec(Bits(16 bits), W)
    val gPreds = Vec(ChunkPredecode(), W)
    for (i <- 0 until W) {
      when(U(i) < count) { gWords(i) := words(i); gPreds(i) := rawPreds(i) } otherwise {
        gWords(i) := 0
        gPreds(i).lenWords := 0; gPreds(i).ambiguousLine := False
        gPreds(i).size := m68k040.isa.Size.BYTE; gPreds(i).ctrlXfer := False
      }
    }
    val avail    = Mux(count > W, U(W, 4 bits), count.resize(4))
    val availEff = Mux(availIn < avail, availIn, avail)
    val hiP1     = headPc(31 downto 5) + 1
    val ref = Aligner.align(headPc, gWords, gPreds, availEff, p0Live, headPcHiP1 = Some(hiP1))
    val ret = Aligner.align(headPc, gWords, rawPreds, availEff, p0Live, headPcHiP1 = Some(hiP1), retime = true)
    def same(a: DecodePacket, b: DecodePacket): Bool =
      a.pc === b.pc && a.words.asBits === b.words.asBits && a.wordCount === b.wordCount &&
      a.simple === b.simple && a.lenWords === b.lenWords && a.complex === b.complex &&
      a.fault === b.fault && a.size === b.size
    mismatch := !(ref.slot0Valid === ret.slot0Valid && ref.slot1Valid === ret.slot1Valid &&
                  ref.shiftWords === ret.shiftWords && ref.stall === ret.stall &&
                  ref.complex === ret.complex) ||
                (ref.slot0Valid && !same(ref.slot0, ret.slot0)) ||
                (ref.slot1Valid && !same(ref.slot1, ret.slot1))
    slot1V := ref.slot1Valid
    slot0V := ref.slot0Valid
  }

  test("CPU_FE_RETIME aligner: ungated per-candidate slot-1 == original on the gated head") {
    M68kSim().withVerilator.compile(new AlignerEquivDut).doSim(seed = 7) { dut =>
      val rnd = new Random(7)
      var s0 = 0; var s1 = 0
      val N = 200000
      for (n <- 0 until N) {
        // Bias lengths toward the legal short range so slot1 is exercised heavily.
        def len(): Int = if (rnd.nextInt(8) == 0) rnd.nextInt(16) else 1 + rnd.nextInt(4)
        for (i <- 0 until W) {
          dut.words(i) #= (if (rnd.nextInt(3) == 0) 0x4E75 + rnd.nextInt(3) else rnd.nextInt(65536))
          dut.rawPreds(i).lenWords #= len()
          dut.rawPreds(i).ambiguousLine #= rnd.nextInt(10) == 0
          dut.rawPreds(i).size #= m68k040.isa.Size.elements(rnd.nextInt(m68k040.isa.Size.elements.size))
          dut.rawPreds(i).ctrlXfer #= rnd.nextBoolean()
        }
        dut.count #= rnd.nextInt(21)
        dut.availIn #= (if (rnd.nextInt(4) == 0) rnd.nextInt(16) else 15)
        dut.p0Live.lenWords #= len()
        dut.p0Live.ambiguousLine #= rnd.nextInt(6) == 0
        dut.p0Live.size #= m68k040.isa.Size.elements(rnd.nextInt(m68k040.isa.Size.elements.size))
        dut.p0Live.ctrlXfer #= false
        dut.headPc #= (rnd.nextLong() & 0xFFFFFFFEL)
        sleep(1)
        assert(!dut.mismatch.toBoolean, s"retimed aligner diverged at vector $n")
        if (dut.slot0V.toBoolean) s0 += 1
        if (dut.slot1V.toBoolean) s1 += 1
      }
      println(f"FE_RETIME aligner equiv: $N vectors, slot0Valid=$s0 slot1Valid=$s1")
      assert(s1 > N / 20, s"slot-1 coverage too thin: $s1")
    }
  }

  class IbufEquivDut extends Component {
    val a = new InstructionBuffer(false)
    val b = new InstructionBuffer(true)
    val pushValid = in Bool()
    val pushWords = in Vec(Bits(16 bits), 4)
    val pushLen   = in Vec(UInt(4 bits), 4)
    val pushN     = in UInt(3 bits)
    val flush     = in Bool()
    val shEn      = in Bool()
    val shA       = in UInt(4 bits)
    val shB       = in UInt(4 bits)
    val shSelA    = in Bool()
    val cnt       = out UInt(5 bits)
    val ready     = out Bool()
    val mismatch  = out Bool()
    // Legal stimulus: a shift never exceeds the occupancy (FetchAlign guarantees it).
    val shAe = Mux(shA.resize(5) > a.io.cnt, a.io.cnt.resize(4), shA)
    val shBe = Mux(shB.resize(5) > a.io.cnt, a.io.cnt.resize(4), shB)
    for (ib <- Seq(a, b)) {
      ib.io.push.valid := pushValid
      for (j <- 0 until 4) {
        ib.io.push.payload.words(j) := pushWords(j)
        ib.io.push.payload.preds(j).lenWords := pushLen(j)
        ib.io.push.payload.preds(j).ambiguousLine := pushLen(j)(3)
        ib.io.push.payload.preds(j).size := m68k040.isa.Size.WORD
        ib.io.push.payload.preds(j).ctrlXfer := pushLen(j)(2)
      }
      ib.io.push.payload.n := pushN
      ib.io.flush := flush
      ib.io.shift := Mux(shEn, Mux(shSelA, shAe, shBe), U(0, 4 bits))
    }
    b.io.shiftEn := shEn; b.io.shiftA := shAe; b.io.shiftB := shBe; b.io.shiftSelA := shSelA
    cnt   := a.io.cnt
    ready := a.io.push.ready
    mismatch := a.io.cnt =/= b.io.cnt || a.io.avail =/= b.io.avail ||
                a.io.head.asBits =/= b.io.head.asBits || a.io.headPred.asBits =/= b.io.headPred.asBits ||
                a.io.push.ready =/= b.io.push.ready
  }

  test("CPU_FE_RETIME IBuf: candidate head/count next-state == original") {
    M68kSim().withVerilator.compile{ val d = new IbufEquivDut; d.a.headPtr.simPublic(); d.b.headPtr.simPublic(); d.a.io.simPublic(); d.b.io.simPublic(); d }.doSim(seed = 11) { dut =>
      val rnd = new Random(11)
      dut.clockDomain.forkStimulus(10)
      dut.flush #= true; dut.pushValid #= false; dut.shEn #= false
      dut.shA #= 0; dut.shB #= 0; dut.shSelA #= false; dut.pushN #= 0
      for (j <- 0 until 4) { dut.pushWords(j) #= 0; dut.pushLen(j) #= 0 }
      dut.clockDomain.waitSampling(2)
      var shifted = 0L
      for (n <- 0 until 50000) {
        val c = dut.cnt.toInt
        dut.flush #= rnd.nextInt(200) == 0
        dut.pushValid #= dut.ready.toBoolean && rnd.nextInt(3) != 0
        dut.pushN #= 1 + rnd.nextInt(4)
        for (j <- 0 until 4) { dut.pushWords(j) #= rnd.nextInt(65536); dut.pushLen(j) #= rnd.nextInt(16) }
        val lim = scala.math.min(c, 15)
        val a = rnd.nextInt(lim + 1); val b = rnd.nextInt(lim + 1)
        dut.shA #= a; dut.shB #= b; dut.shSelA #= rnd.nextBoolean()
        dut.shEn #= rnd.nextInt(3) != 0
        sleep(1)
        if (dut.mismatch.toBoolean) {
          println(s"cyc $n a.cnt=${dut.a.io.cnt.toInt} b.cnt=${dut.b.io.cnt.toInt} a.avail=${dut.a.io.avail.toInt} b.avail=${dut.b.io.avail.toInt} " +
            s"a.rdy=${dut.a.io.push.ready.toBoolean} b.rdy=${dut.b.io.push.ready.toBoolean} " +
            s"aH=${dut.a.io.head.map(_.toInt).mkString(",")} bH=${dut.b.io.head.map(_.toInt).mkString(",")} " +
            s"aP=${dut.a.io.headPred.map(_.lenWords.toInt).mkString(",")} bP=${dut.b.io.headPred.map(_.lenWords.toInt).mkString(",")} " +
            s"aHp=${dut.a.headPtr.toInt} bHp=${dut.b.headPtr.toInt}")
        }
        assert(!dut.mismatch.toBoolean, s"retimed IBuf diverged at cycle $n")
        if (dut.shEn.toBoolean) shifted += 1
        dut.clockDomain.waitSampling()
      }
      assert(shifted > 10000)
    }
  }
}
