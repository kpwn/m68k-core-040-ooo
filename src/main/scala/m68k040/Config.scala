package m68k040

import spinal.core._

/** Compile-time sizing for the whole core (spec Appendix A). All depths are
  * parameters so IPC/area/FMax can be swept without rearchitecting. */
case class M68kParams(
    // ROB 32 + PRF 54 (2026-09-14). The PRF, not the ROB, used to bound the window:
    // 50 phys - 20 arch = 30 in-flight renames against a 64-entry ROB, so more than
    // half the ROB was unreachable for int-heavy code.
    // 54 = 20 arch + 32 ROB + 2, the last 2 being what 2-wide rename needs in hand so
    // the freelist cannot stall in the cycle it allocates before a retire frees.
    // log2Up(54) = 6, the SAME width as 50 -- no address field, bypass comparator or
    // IQ scoreboard bitmap widens. robIdWidth drops 6 -> 5 across ~339 references.
    robDepth:     Int = 32,
    physInt:      Int = 54,
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
