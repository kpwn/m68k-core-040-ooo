package m68k040.debug

import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Vivado VIO observation/actuation group (design spec `2026-08-18-vio-jtag-debug-design.md`,
  * V2-V6). Granted a deliberate, enumerated exception to axi-socket D23 ("the socket top
  * exports only socket ports") for the same reason group 7's ILA export already has one in
  * `macqd700-soc/rtl/soc/cpu_socket.vh:178-196`: a polled `dbg_axi` register read cannot be
  * performed at all when `dbg_axi` is the thing that is wedged, and cannot be performed during
  * reset under any circumstances (spec section 1).
  *
  * `enable` defaults FALSE (V4) -- with it false this Area elaborates to nothing, so
  * `M68kFullCoreSynth`'s port surface stays exactly what axi-socket D23 froze it as. The socket
  * top (once it exists, per V5) instantiates this with `enable=true` and no gate (V3): the SoC
  * decides whether to bind the group to a real VIO IP, and an unbound group-8 output on a
  * VIO-less build is simply an unconnected output.
  *
  * Placed AFTER RobPlugin in any plugin list that includes it, since Task 2 consumes
  * `CommitTraceService`/`FrontendQuiesceService`/`host[RobPlugin]` -- none of which exist until
  * RobPlugin has built. */
class VioProbePlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    if (enable) {
      // Probe RTL lands in Task 2 (probe_in bundle) and Task 3 (probe_out group). This
      // skeleton deliberately declares nothing yet -- proving the plugin's OWN construction
      // and placement are clean before any probe logic can obscure that.
    }
  }
}
