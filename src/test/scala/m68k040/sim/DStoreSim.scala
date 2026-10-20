package m68k040.sim

import m68k040.cache.DStoreCmd
import m68k040.isa.Size
import spinal.core.sim._

/** Sim-side view of a `DStoreCmd` that works in both payload shapes:
  * explicit lanes (`strb`/`lineData`, default) and `CPU_DSTORE_COMPACT`
  * (`tailB`/`tailOff`, lanes derived from {offset, size, data}). */
object DStoreSim {
  def compact: Boolean = m68k040.top.ShippingCoreConfig.dstoreCompact

  def sizeBytes(p: DStoreCmd): Int = p.size.toEnum match {
    case Size.BYTE => 1
    case Size.WORD => 2
    case _         => 4
  }

  /** Lanes of a store at lane origin `o5` (0..31; 16+off = the tail of an access at `off`
    * of the previous line). Mirrors `DcacheByteLane.storeDataX/storeStrbX`. */
  def lanesOf(o5: Int, n: Int, data: Long): (Int, BigInt) = {
    var strb = 0; var line = BigInt(0)
    for (k <- 0 until n) {
      val pos = (o5 + k) & 31
      if (pos < 16) {
        strb |= 1 << pos
        line |= BigInt((data >> (8 * (n - 1 - k))) & 0xff) << (8 * pos)
      }
    }
    (strb, line)
  }

  /** True when the store's bytes are given by an explicit strobe (legacy `useStrb`) or,
    * in compact mode, when it is a split slot B. */
  def strobed(p: DStoreCmd): Boolean =
    if (compact) p.tailB.toBoolean else p.useStrb.toBoolean

  /** (line-relative strobe, 128-bit line data) of the store, in either shape. */
  def lanes(p: DStoreCmd): (Int, BigInt) = {
    val paddr = p.paddr.toLong & 0xffffffffL
    val data  = p.data.toLong & 0xffffffffL
    if (compact) {
      val o5 = if (p.tailB.toBoolean) 16 + p.tailOff.toInt else (paddr & 0xf).toInt
      lanesOf(o5, sizeBytes(p), data)
    } else if (p.useStrb.toBoolean) (p.strb.toInt & 0xffff, p.lineData.toBigInt)
    else lanesOf((paddr & 0xf).toInt, sizeBytes(p), data)
  }

  /** (byte address, value) of every byte the store writes. A strobed store (legacy
    * `useStrb`, compact slot B / any slot via its lanes) is read from its lanes; any other
    * store is `size` bytes big-endian from `paddr` (the historical capture rule). */
  def byteWrites(p: DStoreCmd): Seq[(Long, Int)] = {
    val paddr = p.paddr.toLong & 0xffffffffL
    if (strobed(p) || (compact && p.useStrb.toBoolean)) {
      val base = paddr & ~0xfL
      val (strb, line) = lanes(p)
      (0 until 16).filter(i => ((strb >> i) & 1) == 1).map(i => (base + i, ((line >> (8 * i)) & 0xff).toInt))
    } else {
      val n = sizeBytes(p); val data = p.data.toLong & 0xffffffffL
      (0 until n).map(k => (paddr + k, ((data >> (8 * (n - 1 - k))) & 0xff).toInt))
    }
  }

  /** Drive the no-explicit-lane fields to their "plain store" values in either shape. */
  def drivePlain(p: DStoreCmd): Unit = {
    p.useStrb #= false
    if (compact) { p.tailB #= false; p.tailOff #= 0 }
    else { p.strb #= 0; p.lineData #= 0 }
  }

  /** Drive an explicit-lane store (legacy shape only). */
  def driveLanes(p: DStoreCmd, strb: Int, line: BigInt): Unit = {
    // Under CPU_DSTORE_COMPACT no producer can emit an explicit-lane (e.g. full-line) store,
    // so a scenario built on one does not exist: cancel, do not fail.
    if (compact) throw new org.scalatest.exceptions.TestCanceledException(
      "CPU_DSTORE_COMPACT: explicit-lane store shape does not exist", 0)
    p.useStrb #= true; p.strb #= strb; p.lineData #= line
  }
}
