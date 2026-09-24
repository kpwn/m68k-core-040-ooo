package m68k040.top

/** THE SHIPPING CORE'S CONFIGURATION, in one place.
  *
  * Why this exists: the plugin knobs used to live as constructor DEFAULTS, and the
  * shipping top (`SocketTop`) and the benches each built their own plugin lists. Changing
  * a knob therefore meant editing a default that both happened to read -- and since the
  * SoC build regenerates its Verilog from a SEPARATE cpu040 checkout, "the config I
  * simulated" and "the config that was built" were two different things that had to be
  * kept in sync BY HAND. That cost real board measurements: a bisect image could not be
  * proven to contain the configuration it was labelled with.
  *
  * Rule: a knob that differs between sim and the shipping build is a BUG. Anything a
  * bench or test DUT wants to vary must be varied explicitly and named in the test, never
  * by diverging from these values. */
object ShippingCoreConfig {
  /** D-cache: serve a resolved command from its early-probe entry while the FSM refills
    * for an older, provably cacheable access. See `DcachePlugin.hitUnderMiss`. */
  val dcacheHitUnderMiss: Boolean = true

  /** D-cache: additionally accept such a command with a REAL S1 array read, parking an
    * S1 miss in the bounded replay slot. See `DcachePlugin.hitUnderMissRead`. */
  val dcacheHitUnderMissRead: Boolean = true
}
