package m68k040.synth

import m68k040.execute._
import m68k040.execute.fpu.FpSource
import m68k040.isa.Size
import spinal.core._

/** Module-level OOC wrappers for the redundancy-hunt levers (old vs new arm, same
  * registers at the same stage boundaries). `sbt "Test/runMain m68k040.synth.GenRedexeSynth <dir>"`. */
class ShiftPipeSynth(funnel: Boolean) extends Component {
  val io = new Bundle { val cmd = in(ShiftCmd()); val rsp = out(ShiftRsp()) }
  val c = RegNext(io.cmd)
  val r = if (!funnel) Shifter.stage2(RegNext(Shifter.stage1b(RegNext(Shifter.stage1a(c)))))
          else ShifterFunnel.stage2(RegNext(ShifterFunnel.stage1b(RegNext(ShifterFunnel.stage1a(c)))))
  io.rsp := RegNext(r)
}
class FpConvSynth(shared: Boolean, sharedClz: Boolean = true) extends Component {
  val io = new Bundle {
    val isInt, isSgl = in Bool(); val iv, sv = in Bits(32 bits); val dv = in Bits(64 bits)
    val o = out Bits(80 bits)
  }
  val ii = RegNext(io.isInt); val ss = RegNext(io.isSgl)
  val iv = RegNext(io.iv); val sv = RegNext(io.sv); val dv = RegNext(io.dv)
  val r = if (!shared) Mux(ii, FpSource.intToExtended(iv), Mux(ss, FpSource.singleToExtended(sv), FpSource.doubleToExtended(dv)))
          else FpSource.toExtendedShared(ii, ss, iv, sv, dv, sharedClz)
  io.o := RegNext(r)
}
class BitfieldSynth(trim: Boolean) extends Component {
  val io = new Bundle { val cmd = in(BitfieldCmd()); val rsp = out(BitfieldRsp()) }
  val c = RegNext(io.cmd)
  val r = if (!trim) Bitfield.stage2(RegNext(Bitfield.stage1(c))) else Bitfield.stage2T(RegNext(Bitfield.stage1T(c)))
  io.rsp := RegNext(r)
}
object GenRedexeSynth extends App {
  val d = args(0)
  for ((n, f) <- Seq[(String, () => Component)](
      "ShiftOld" -> (() => new ShiftPipeSynth(false)), "ShiftNew" -> (() => new ShiftPipeSynth(true)),
      "FpOld" -> (() => new FpConvSynth(false)), "FpNew" -> (() => new FpConvSynth(true)), "FpNewB" -> (() => new FpConvSynth(true, false)),
      "BfOld" -> (() => new BitfieldSynth(false)), "BfNew" -> (() => new BitfieldSynth(true)))) {
    SpinalConfig(targetDirectory = s"$d/$n").generateVerilog { val c = f(); c.setDefinitionName(n); c }
  }
}
