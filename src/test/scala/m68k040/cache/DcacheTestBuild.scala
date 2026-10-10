package m68k040.cache

import m68k040.top.ShippingCoreConfig

/** Which L1D a standalone D-cache harness elaborates.
  *
  * `Shipping` is whatever `ShippingCoreConfig` says (the integ default is the NON-BLOCKING
  * L1D with the P6 hot door). `Legacy` is the blocking FSM L1D (`CPU_DCACHE_NONBLOCKING=0`),
  * which is still a selectable build: tests whose subject is a mechanism that exists ONLY
  * in that FSM (the direct-refill response, the hit-under-miss shadow slot, the load-miss
  * store barrier, the victim-snapshot forwards, the legacy full-line no-fill hook) pin it
  * explicitly instead of failing on, or vacuously passing in, a build that does not have it.
  *
  * Every flag that `DcachePlugin` requires to be off without `nonBlocking` is forced off
  * together in `Legacy`, so a pinned DUT elaborates under any shipping environment. */
final case class DcacheTestBuild(nonBlocking: Boolean, hotDoor: Boolean, nbEarlyResponse: Boolean,
                                 nbEagerAr: Boolean, nbPreselectAr: Boolean,
                                 nbDynamicRelease: Boolean, nbProbeMissStage: Boolean)

object DcacheTestBuild {
  val Shipping: DcacheTestBuild = DcacheTestBuild(
    nonBlocking      = ShippingCoreConfig.dcacheNonBlocking,
    hotDoor          = ShippingCoreConfig.dcacheHotDoor,
    nbEarlyResponse  = ShippingCoreConfig.dcacheNbEarlyResponse,
    nbEagerAr        = ShippingCoreConfig.dcacheNbEagerAr,
    nbPreselectAr    = ShippingCoreConfig.dcacheNbPreselectAr,
    nbDynamicRelease = ShippingCoreConfig.dcacheNbDynamicRelease,
    nbProbeMissStage = ShippingCoreConfig.dcacheNbProbeMissStage)
  val Legacy: DcacheTestBuild = DcacheTestBuild(false, false, false, false, false, false, false)
}
