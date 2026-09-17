package m68k040

import spinal.core._

/** Compile-time sizing for the whole core (spec Appendix A). All depths are
  * parameters so IPC/area/FMax can be swept without rearchitecting. */
case class M68kParams(
    // ROB depth and int phys-reg pool. Both are DERIVED from the single source of
    // truth in `Global`, never restated here -- see `Global.ROB_DEPTH_DEFAULT` and
    // `Global.PHYS_INT_REGS_DEFAULT` for why each is a plain constant and what the
    // `require`s in `ParamPlugin`/`IssueQueuePlugin` are protecting.
    //
    // MERGE NOTE (2026-09-17): master's note here said "⚠️ 32 DOES NOT ELABORATE YET",
    // blocked on LsEuPlugin packing the D-cache load token as `False ## bDone ## robId`
    // = 1+1+6 = 8 bits, which a 5-bit robId narrows to 7. That blocker is RESOLVED, and
    // resolved the deliberate way the note asked for: `Global.robTag` pads the tag to
    // the port's fixed width on the left, preserving field order and the reserved
    // $80/$81/$82 split, so the cross-module contract with DcachePlugin's early-probe
    // CAM is unchanged. The stale warning is dropped; the two below are still live.
    //
    // DO NOT widen `pdst` along the way -- pdst is 6 bits because there are 54 PHYSICAL
    // REGISTERS, not because of the ROB. Conflating the two is how this breaks.
    //
    // ⚠️ VERIFY BY THE NETLIST, NOT BY "a build ran": `sysValStore_32..63` must VANISH
    // from generated/M68kFullCoreSynth.v. A rebuild was once confirmed, the ROB had NOT
    // shrunk, and the resulting "IPC-neutral" reading was neutral precisely because
    // nothing had changed.
    robDepth:     Int = Global.ROB_DEPTH_DEFAULT,
    physInt:      Int = Global.PHYS_INT_REGS_DEFAULT,
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
