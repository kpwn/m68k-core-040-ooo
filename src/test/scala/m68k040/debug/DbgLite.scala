package m68k040.debug

import org.scalatest.Assertions

/** CPU_DBG_LITE test support. `DebugCtrlPlugin.lite` defaults to
  * `ShippingCoreConfig.dbgLite`, so with `CPU_DBG_LITE=1` every directly-built debug DUT
  * is the lite build. Specs of features the lite build drops CANCEL through `skip`
  * (reported as canceled, never as passed); specs of kept features stay live and check
  * the lite behaviour where it differs (dropped offsets read 0). */
object DbgLite {
  val on: Boolean = m68k040.top.ShippingCoreConfig.dbgLite

  /** Cancel the calling test in a lite build: `what` is not built there. */
  def skip(what: String): Unit =
    Assertions.assume(!on, s"lite build (CPU_DBG_LITE=1): $what unavailable")

  val featureBit: Int = DebugRegMap.features.find(_._1 == "dbg_lite").get._2
  /** The OFF_FEATURES identity bit a lite build adds (0 otherwise). */
  def featureMask: Long = if (on) 1L << featureBit else 0L
}
