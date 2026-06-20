package m68k040

import spinal.core._

/** Compile-time sizing for the whole core (spec Appendix A). All depths are
  * parameters so IPC/area/FMax can be swept without rearchitecting. */
case class M68kParams(
    robDepth:     Int = 64,
    physInt:      Int = 50,
    physNzvc:     Int = 16,
    physX:        Int = 16,
    intRsDepth:   Int = 8,
    eaRsDepth:    Int = 8,
    memRsDepth:   Int = 8,
    cplxRsDepth:  Int = 4,
    loadQDepth:   Int = 8,
    storeQDepth:  Int = 8,
    l1iKb:        Int = 16,
    l1iWays:      Int = 4,
    l1iLineBytes: Int = 64,
    l1dKb:        Int = 16,
    l2Kb:         Int = 1024,
    btbEntries:   Int = 128,
    rasEntries:   Int = 16,
    gshareHistory:Int = 16,
    gshareEntries:Int = 2048,
    tlbWays:      Int = 4,
    tlbSets:      Int = 16,
    decodeWidth:  Int = 2,
    retireWidth:  Int = 2
) {
  // Derived pointer/index widths are ceil-log2; non-power-of-two depths
  // (e.g. physInt=48 -> 6 bits) are valid and intentional.
  val robIdWidth:      Int = log2Up(robDepth)
  val physIntIdWidth:  Int = log2Up(physInt)
  val physNzvcIdWidth: Int = log2Up(physNzvc)
  val physXIdWidth:    Int = log2Up(physX)
  val sqPtrWidth:      Int = log2Up(storeQDepth)
  val lqPtrWidth:      Int = log2Up(loadQDepth)
}
