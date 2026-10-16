package m68k040.top

import m68k040.M68kSpinalConfig
import m68k040.cache.{ChunkPredecode, IcacheInstructionOrder}
import m68k040.frontend.{PredecodeCoded, PredecodeWord}
import spinal.core._

/** OOC probe of the I-cache refill predecode cone (`IcachePlugin.ufaWrData`): fillLo/fillHi
  * (registered) + commitBeat -> beatSrc mux -> 16 x PredecodeWord.classify -> registered
  * {pred, data}, exactly as the 16-wide installer builds it. `field` keeps only part of the
  * per-word record for attribution (all | len | size | ctrl). */
class PredecodeProbe(field: String) extends Component {
  val io = new Bundle {
    val lo = in Bits(256 bits)
    val hi = in Bits(256 bits)
    val isLo = in Bool()
    val q = out Bits(512 bits)
  }
  val lo = RegNext(io.lo); val hi = RegNext(io.hi); val isLo = RegNext(io.isLo)
  val beatSrc   = Mux(isLo, lo, hi)
  val beatNext3 = Mux(isLo, hi(47 downto 0), B(0, 48 bits))
  val preds = if (field == "lenop") {
    val w = beatSrc.subdivideIn(16 bits).map(IcacheInstructionOrder.opword)
    w.map(o => PredecodeWord.classify(o, B(0, 16 bits), B(0, 16 bits), B(0, 16 bits), True, True, True))
  } else PredecodeProbe.classifyBeatRecs(beatSrc, beatNext3, isLo, coded = field == "call" || field == "clen")
  val pb = Cat(preds.map { p => field match {
    case "all" | "call" => p.asBits
    case "len" | "lenop" | "clen" => p.lenWords.asBits ## p.ambiguousLine.asBits
    case "size" => p.size.asBits
    case "ctrl" => p.ctrlXfer.asBits
  }})
  io.q := RegNext((pb ## beatSrc).resize(512))
}

object PredecodeProbe {
  def classifyBeatRecs(beat: Bits, nextLo: Bits, nextValid: Bool, coded: Boolean): Seq[ChunkPredecode] = {
    val chunkWords = beat.getBitsWidth / 16
    val rawW  = beat.subdivideIn(16 bits)
    val rawNx = nextLo.subdivideIn(16 bits)
    val w  = Vec((0 until chunkWords).map(i => IcacheInstructionOrder.opword(rawW(i))))
    val nx = Vec((0 until 3).map(i => IcacheInstructionOrder.opword(rawNx(i))))
    def word(i: Int): Bits =
      if (i < chunkWords) w(i) else Mux(nextValid, nx(i - chunkWords), B(0, 16 bits))
    def wordValid(i: Int): Bool = if (i < chunkWords) True else nextValid
    if (coded) {
      val ixs = (0 until chunkWords + 3).map(j => m68k040.frontend.PredecodeCoded.ix(word(j), wordValid(j)))
      return (0 until chunkWords).map { i =>
        m68k040.frontend.PredecodeCoded.classify(w(i), word(i + 1), word(i + 2), word(i + 3),
          wordValid(i + 1), wordValid(i + 2), wordValid(i + 3), ixs(i + 1), ixs(i + 2), ixs(i + 3))
      }
    }
    (0 until chunkWords).map { i =>
      PredecodeWord.classify(w(i), word(i + 1), word(i + 2), word(i + 3),
        extWValid = wordValid(i + 1), extW2Valid = wordValid(i + 2), extW3Valid = wordValid(i + 3))
    }
  }
}

object GenPredecodeSynthVerilog {
  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse("all")
    val name = "PredecodeSynth" + mode.capitalize
    M68kSpinalConfig(targetDirectory = "generated").generateVerilog(
      new PredecodeProbe(mode).setDefinitionName(name))
    println(s"Generated generated/$name.v")
  }
}

/** OOC probe of the LAZY4 job's classifier group: 4 units over a registered 7-word window
  * (+ window-end validity), CODED form under the coverage cuts `drop`; registered
  * {lenWords, ambiguousLine} x 4 out. `drop` empty = the LAZY4 shipping classifier. */
class PredecodeUnits4Probe(drop: Set[String], coded: Boolean = true) extends Component {
  val io = new Bundle {
    val w = in Vec(Bits(16 bits), 7)
    val lastGroup = in Bool()
    val q = out Bits(20 bits)
  }
  val w = RegNext(io.w); val lastG = RegNext(io.lastGroup)
  val valid = (0 until 7).map(k => if (k < 4) True else !lastG)
  val recs = if (coded) {
    val views = (0 until 7).map(j => PredecodeCoded.ix(w(j), valid(j)))
    (0 until 4).map { i =>
      val v = (1 to 3).map(p => PredecodeCoded.cutView(w(i + p), valid(i + p), p, drop))
      PredecodeCoded.classifyD(w(i), w(i + 1), w(i + 2), w(i + 3), v(0)._1, v(1)._1, v(2)._1,
        if (drop.isEmpty) views(i + 1) else v(0)._2, if (drop.isEmpty) views(i + 2) else v(1)._2,
        if (drop.isEmpty) views(i + 3) else v(2)._2, v.map(_._3), drop)
    }
  } else (0 until 4).map(i => PredecodeWord.classify(w(i), w(i + 1), w(i + 2), w(i + 3),
    extWValid = valid(i + 1), extW2Valid = valid(i + 2), extW3Valid = valid(i + 3)))
  io.q := RegNext(Cat(recs.map(r => r.ambiguousLine ## r.lenWords.asBits)))
}

object GenPredecodeUnits4Verilog {
  def main(args: Array[String]): Unit = {
    // args: name=cuts (cuts '+'-joined, "none" = no cut, "orig" = PredecodeWord)
    for (a <- args) {
      val Array(name, c) = a.split('=')
      val drop = if (c == "none" || c == "orig") Set.empty[String] else c.split('+').toSet
      M68kSpinalConfig(targetDirectory = "generated").generateVerilog(
        new PredecodeUnits4Probe(drop, coded = c != "orig").setDefinitionName("PdU4_" + name))
    }
  }
}
