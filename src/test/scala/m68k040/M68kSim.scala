package m68k040

import spinal.core.sim._

/** SimConfig that applies the core's elaboration config (incl. the multi-write
  * Mem lowering phase), so register-file simulations exercise the lowered
  * XOR/LVT banks — making the existing assertions an equivalence proof for the
  * lowering. Append `.withVerilator` at the call site where the spec used it.
  *
  * `.includeSimulation` is REQUIRED here: without it `SpinalConfig.flags` never
  * contains `GenerationFlags.simulation`, so every `GenerationFlags.simulation { ... }`
  * block in the design (the sim-only fatal asserts in DcachePlugin/StoreQueue/
  * RobPlugin, plus any future sim-only hardware) is silently skipped at elaboration
  * time -- NOT merely "not fatal": literally never even generates the wire/always
  * block (confirmed empirically: the generated Verilog module was completely empty
  * where those blocks should have been). `M68kSpinalConfig()` itself is intentionally
  * left untouched here because it is shared with the real synthesis flow
  * (GenVerilog/FullCoreSynth) -- unconditionally adding `.includeSimulation` there
  * would leak sim-only $finish/$display system tasks into the synthesizable netlist. */
object M68kSim {
  def apply(): SpinalSimConfig = SimConfig.withConfig(M68kSpinalConfig().includeSimulation)
}
