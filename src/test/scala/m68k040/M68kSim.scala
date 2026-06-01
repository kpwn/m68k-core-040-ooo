package m68k040

import spinal.core.sim._

/** SimConfig that applies the core's elaboration config (incl. the multi-write
  * Mem lowering phase), so register-file simulations exercise the lowered
  * XOR/LVT banks — making the existing assertions an equivalence proof for the
  * lowering. Append `.withVerilator` at the call site where the spec used it. */
object M68kSim {
  def apply(): SpinalSimConfig = SimConfig.withConfig(M68kSpinalConfig())
}
