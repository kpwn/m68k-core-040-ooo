package m68k040.top

import m68k040.M68kSpinalConfig
import m68k040.decode.{ImmEnc, MicroOpAssembler}
import m68k040.frontend.DecodePacket
import spinal.core._

/** OOC probe of the decode stage's GROUP-bank immediate cone (`wideImm.grpData`): the two
  * fed packets' `MicroOpAssembler.assemble` imm outputs, exactly as DecodeStage builds
  * them, between registered inputs and a registered 192-bit output (= `grpWrData`).
  * `immCone` selects the CPU_IMM_CONE form. Shipping assembler knobs (fuse, feRetime). */
class ImmConeProbe(immCone: Boolean) extends Component {
  val io = new Bundle {
    val pkts = in(Vec(DecodePacket(), 2))
    val offs = in(Vec(MicroOpAssembler.Offload(), 2))
    val data = out(Bits(ImmEnc.GRP_ENTRY_W bits))
  }
  val pk = RegNext(io.pkts)
  val of = RegNext(io.offs)
  // OFF: `grpWrData = RegNext(grpData)`. ON: DecodeStage's form -- {A,P,K} + pc registered,
  // the add after the register (here followed by an output register so it is timed).
  if (!immCone) {
    io.data := RegNext(MicroOpAssembler.grpImmData(pk, of, fuseLongMoveLoads = true,
      retime = ShippingCoreConfig.feRetime, immCone = false))
  } else {
    val d = Bits(ImmEnc.GRP_ENTRY_W bits)
    for (p <- 0 until 2) {
      val (a, ia) = MicroOpAssembler.assembleAtoms(pk(p), of(p), true, ShippingCoreConfig.feRetime)
      val pcR = RegNext(pk(p).pc)
      for (q <- 0 until ImmEnc.GRP_UOPS_PER_PACKET) {
        val sl = ImmEnc.grpSel(p, q)
        d(32 * sl + 31 downto 32 * sl) := RegNext(ia.parts(a.uops(q).imm)).value(pcR)
      }
    }
    io.data := RegNext(d)
  }
}

/** Attribution probes for the ON form: `codes` = only the one-hot select codes (decision
  * logic), `freeAtoms` = codes + AND-OR with the atom VALUES replaced by free registered
  * inputs (i.e. without the per-packet atom arithmetic). */
class ImmConeAttrProbe(freeAtoms: Boolean) extends Component {
  val io = new Bundle {
    val pkts = in(Vec(DecodePacket(), 2))
    val offs = in(Vec(MicroOpAssembler.Offload(), 2))
    val atomsIn = in(Vec(Bits(32 bits), 128))
    val data = out(Bits((if (freeAtoms) ImmEnc.GRP_ENTRY_W else 6 * m68k040.decode.ImmAtoms.WIDTH) bits))
  }
  val pk = RegNext(io.pkts)
  val of = RegNext(io.offs)
  val ai = RegNext(io.atomsIn)
  val outs = for (p <- 0 until 2; q <- 0 until 3) yield {
    val (a, ia) = MicroOpAssembler.assembleAtoms(pk(p), of(p), true, ShippingCoreConfig.feRetime)
    if (freeAtoms) {
      val code = a.uops(q).imm
      (0 until ia.aAtoms.length).map(k => ai(p * 64 + k) & B(32 bits, default -> code(k))).reduce(_ | _)
    } else a.uops(q).imm
  }
  io.data := RegNext(Cat(outs))
}

object GenImmConeSynthVerilog {
  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse("off")
    val name = "ImmConeSynth" + mode.capitalize
    M68kSpinalConfig(targetDirectory = "generated").generateVerilog(mode match {
      case "codes"     => new ImmConeAttrProbe(false).setDefinitionName(name)
      case "freeatoms" => new ImmConeAttrProbe(true).setDefinitionName(name)
      case m           => new ImmConeProbe(m == "on").setDefinitionName(name)
    })
    println(s"Generated generated/$name.v")
  }
}
