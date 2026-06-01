package m68k040

import m68k040.hw.MultiPortWritesSymplifier
import spinal.core._

/** Single source of truth for the core's SpinalHDL elaboration config. Adds the
  * multi-write-Mem lowering phase so RAT/Freelist/ROB-payload synthesize on FPGA
  * (no FPGA RAM primitive has >1 write port). Used by both Verilog generation
  * and the register-file specs' simulation (so tests exercise the lowered logic). */
object M68kSpinalConfig {
  def apply(targetDirectory: String = null): SpinalConfig = {
    val base = if (targetDirectory != null) SpinalConfig(targetDirectory = targetDirectory) else SpinalConfig()
    base.addTransformationPhase(new MultiPortWritesSymplifier())
  }
}
