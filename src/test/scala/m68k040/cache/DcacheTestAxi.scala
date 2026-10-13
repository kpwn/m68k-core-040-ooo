package m68k040.cache

import spinal.core.sim._

/** Bus-side observations of a standalone `DcachePlugin` that hold in BOTH L1D builds.
  *
  * With `nonBlocking && hotDoor` (the shipping default) every cacheable refill leaves on
  * the read-only hot door `axiDh`, not on `axi`; `axi` keeps the cold traffic (inhibited
  * reads, write-through/eviction writes). A test that counts refills on `axi` alone
  * therefore reads 0 under the shipping configuration. These helpers look at both doors
  * exactly as the SoC crossbar does (the hot door is served by `HotDoorAttach`). */
object DcacheTestAxi {
  /** An AR handshake on the cold port. */
  def coldArFire(dc: DcachePlugin): Boolean =
    dc.logic.axi.ar.valid.toBoolean && dc.logic.axi.ar.ready.toBoolean
  /** An AR handshake on the hot door (false when the DUT has none). */
  def hotArFire(dc: DcachePlugin): Boolean =
    dc.hotDoor && dc.logic.axiDh.ar.valid.toBoolean && dc.logic.axiDh.ar.ready.toBoolean
  /** An AR handshake on either read door. */
  def arFire(dc: DcachePlugin): Boolean = coldArFire(dc) || hotArFire(dc)
  /** Physical addresses of this cycle's AR handshakes on either read door. */
  def arFireAddrs(dc: DcachePlugin): Seq[Long] =
    (if (coldArFire(dc)) Seq(dc.logic.axi.ar.payload.addr.toLong & 0xffffffffL) else Nil) ++
    (if (hotArFire(dc)) Seq(dc.logic.axiDh.ar.payload.addr.toLong & 0xffffffffL) else Nil)
  /** AR valid on either read door (handshake not required). */
  def arValid(dc: DcachePlugin): Boolean =
    dc.logic.axi.ar.valid.toBoolean || (dc.hotDoor && dc.logic.axiDh.ar.valid.toBoolean)

  /** D4 (`CPU_INHIBITED_FULL_BARRIER`): the LS EU launches an INHIBITED access only once
    * `DcachePlugin.busQuiesced` is high (`LsEuPlugin.dLaunchOk`); the non-blocking L1D
    * asserts that contract. A harness that drives the cache directly must honour it the
    * same way before offering an inhibited load. No-op when the DUT has no D4 export. */
  def awaitInhibitedLaunchOk(dc: DcachePlugin, cd: spinal.core.ClockDomain): Unit =
    if (dc.logic.busQuiescedReg != null) cd.waitSamplingWhere(dc.logic.busQuiescedReg.toBoolean)

  /** Wait until every accepted transaction has finished (`busQuiesced`), so the tag and
    * valid arrays can be inspected. The non-blocking L1D answers a load from its MSHR
    * (`nbEarlyResponse`) BEFORE the refill has written the arrays; a test that peeks the
    * arrays right after a load response must wait for this first. Falls back to a fixed
    * settle when the DUT has no D4 export. */
  def awaitQuiet(dc: DcachePlugin, cd: spinal.core.ClockDomain): Unit =
    if (dc.logic.busQuiescedReg != null) { cd.waitSampling(); awaitInhibitedLaunchOk(dc, cd) }
    else cd.waitSampling(32)

  /** Drive the ring-ordering fields of a harness load port to the plain IN-ORDER form
    * (`ooOk = ridValid = lineOnly = False`, `rid = 0`). These are DUT INPUTS: a harness
    * that never assigns them leaves them at the simulator's (randomised) initial value,
    * and the non-blocking L1D then treats the loads as out-of-order ring traffic with a
    * random `rid` -- its own `waiter slot reused` / `ring slot re-sent` assertions fire.
    * Tests that exercise those fields still assign them explicitly afterwards. */
  def inOrderLoadDefaults(p: spinal.lib.Stream[DLoadCmd]): Unit = {
    p.payload.ooOk #= false
    p.payload.ridValid #= false
    p.payload.rid #= 0
    p.payload.lineOnly #= false
  }
}
