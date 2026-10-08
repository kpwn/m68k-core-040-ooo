package m68k040.cache

import spinal.core._
import spinal.core.sim._

/** Sim backdoor to the L1D per-way valid/dirty bits that works for both array layouts:
  * the single `validsMem(w)`/`dirtysMem(w)` Mem, and the `CPU_DCACHE_VD_RETIME` layout
  * where set `s` lives in `validsBank(w)(s >> vdLoBits)` at index `s & (2^vdLoBits - 1)`. */
object DcacheVdPeek {
  private def locate(p: DcachePlugin, dirty: Boolean, w: Int, set: Int): (Mem[Bool], Int) = {
    val l = p.logic
    if (l.vdBanked) {
      val bank = if (dirty) l.dirtysBank else l.validsBank
      (bank(w)(set >> l.vdLoBits), set & ((1 << l.vdLoBits) - 1))
    } else (if (dirty) l.dirtysMem(w) else l.validsMem(w), set)
  }
  def valid(p: DcachePlugin, w: Int, set: Int): Boolean = {
    val (m, i) = locate(p, dirty = false, w, set); m.getBigInt(i) != 0
  }
  def dirty(p: DcachePlugin, w: Int, set: Int): Boolean = {
    val (m, i) = locate(p, dirty = true, w, set); m.getBigInt(i) != 0
  }
  def setValid(p: DcachePlugin, w: Int, set: Int, v: Boolean): Unit = {
    val (m, i) = locate(p, dirty = false, w, set); m.setBigInt(i, if (v) BigInt(1) else BigInt(0))
  }
}
