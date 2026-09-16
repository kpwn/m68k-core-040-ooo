package m68k040.synth

import m68k040.M68kSpinalConfig
import m68k040.frontend.PredecodeWord
import spinal.core._

/** Measurement-only harness: the SAME 16-instance predecode group `IcachePlugin`
  * instantiates per refill beat (`classifyBeat`), with a registered IO boundary so
  * out-of-context synthesis measures the classify cone and nothing else.
  *
  * Two variants that differ ONLY in whether `ctrlXfer` is driven out. In the `NoCtrl`
  * variant nothing reads that field, so synthesis prunes the whole control-transfer
  * comparator subtree exactly as it did before the field existed -- which makes the LUT
  * difference between the two builds the MEASURED cost of the added classifier, with no
  * stash/regenerate step and no confounding from the widened line array.
  *
  * The measured RTL change itself is INSTANCE-COUNT AGNOSTIC: `ctrlXfer` is computed
  * inside `PredecodeWord.classify`, so it rides whatever grouping `IcachePlugin` uses. If
  * the pending "time-multiplex the 16 parallel predecode instances down to 8" proposal
  * lands, the per-instance cost measured here is unchanged and the group total simply
  * halves with everything else -- only the `16` in this harness would need updating.
  *
  * Not part of any test; run via
  *   sbt "Test/runMain m68k040.synth.GenPredecodeGroupCtrl"
  *   sbt "Test/runMain m68k040.synth.GenPredecodeGroupNoCtrl"
  */
class PredecodeGroup(withCtrlXfer: Boolean) extends Component {
  val wordsIn = in Vec(Bits(16 bits), 19)      // 16 beat words + 3 lookahead
  val words   = Vec(wordsIn.map(RegNext(_)))  // registered IO boundary
  val simpleOut   = out Bits(16 bits)
  val lenOut      = out Bits(64 bits)
  val ambOut      = out Bits(16 bits)
  val sizeOut     = out Bits(32 bits)
  val ctrlOut     = out Bits(16 bits)

  val s = Bits(16 bits); val l = Bits(64 bits); val a = Bits(16 bits)
  val z = Bits(32 bits); val c = Bits(16 bits)
  for (i <- 0 until 16) {
    val r = PredecodeWord.classify(words(i), words(i + 1), words(i + 2), words(i + 3),
      extWValid = true, extW2Valid = true, extW3Valid = true)
    s(i)                   := r.simple
    l(i * 4 + 3 downto i * 4) := r.lenWords.asBits
    a(i)                   := r.ambiguousLine
    z(i * 2 + 1 downto i * 2) := r.size.asBits
    c(i)                   := (if (withCtrlXfer) r.ctrlXfer else False)
  }
  simpleOut := RegNext(s); lenOut := RegNext(l); ambOut := RegNext(a)
  sizeOut   := RegNext(z); ctrlOut := RegNext(c)
}

object GenPredecodeGroupCtrl {
  def main(args: Array[String]): Unit = {
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new PredecodeGroup(true).setDefinitionName("PredecodeGroupCtrl"))
    println("Generated generated/PredecodeGroupCtrl.v")
  }
}
object GenPredecodeGroupNoCtrl {
  def main(args: Array[String]): Unit = {
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new PredecodeGroup(false).setDefinitionName("PredecodeGroupNoCtrl"))
    println("Generated generated/PredecodeGroupNoCtrl.v")
  }
}
