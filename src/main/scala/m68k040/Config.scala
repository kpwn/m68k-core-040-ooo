package m68k040

import spinal.core._

/** Compile-time sizing for the whole core (spec Appendix A). All depths are
  * parameters so IPC/area/FMax can be swept without rearchitecting. */
case class M68kParams(
    // ROB depth. RobPlugin now DERIVES its array size from this (it used to hardcode
    // 64, which made this knob a lie -- see the 2026-09-14 note there).
    // ⚠️ 32 DOES NOT ELABORATE YET -- the refactor is ~45 sites in and NOT finished.
    // Ten iterations each uncovered one more class; the last stop was
    // LsEuPlugin.scala:1410. Remaining work is mechanical but long: every robId-carrying
    // width must derive from Global.ROB_ID_W, and every ROB-indexed array from
    // ROB_DEPTH. DO NOT widen `pdst` along with them -- pdst is 6 bits because there
    // are 54 PHYSICAL REGISTERS, and conflating the two is how this breaks.
    //
    // ⚠️ VERIFY BY THE NETLIST, NOT BY "a build ran": `sysValStore_32..63` must VANISH
    // from generated/M68kFullCoreSynth.v. Earlier today a rebuild was confirmed, the
    // ROB had NOT shrunk, and the resulting "IPC-neutral" reading was neutral precisely
    // because nothing had changed.
    // The payoff when done: ROB per-entry state (~19,318 cells) halves and robIdWidth
    // drops 6 -> 5 across ~339 references -- the largest congestion lever left for the
    // 200 MHz SoC, which closes standalone (+0.007) but misses by 0.5-1.2 ns integrated.
    robDepth:     Int = 64,
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
