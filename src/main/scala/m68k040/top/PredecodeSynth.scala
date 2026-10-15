package m68k040.top

import m68k040.M68kSpinalConfig
import m68k040.cache.{ChunkPredecode, IcacheInstructionOrder}
import m68k040.frontend.PredecodeWord
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
