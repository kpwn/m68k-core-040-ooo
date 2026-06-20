package m68k040.frontend

import m68k040.Global
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** Return-address stack (RAS) for RTS/RTR prediction (frontend predictor, slice 2).
  *
  * A small circular stack of return PCs in the fetch front-end, parallel to the
  * BtbPlugin (NOT entangled with it). On a fetched call (BSR/JSR) FetchAlign pushes
  * the call's fall-through PC; on a fetched return (RTS/RTR) it pops + predicts the
  * top-of-stack as the return target. The prediction rides the EXISTING slice-1
  * predTaken/predTarget carry-down to the branch EU, which always verifies the real
  * return target (loaded from the stack) — so a corrupt RAS is only a perf loss,
  * never a correctness bug (recovery = the existing commit-time redirect).
  *
  * Recovery scheme = ACCEPT CORRUPTION: the speculative rasSp/count/contents are NOT
  * checkpointed or restored on a flush. The EU-verify covers any wrong guess.
  *
  * Interface (directionless plain wires, the BtbPlugin/IcachePlugin convention —
  * driven/read by the wiring layer; idle-defaulted with concrete zeros so a
  * standalone DUT elaborates):
  *  - pushValid / pushRetPc : push the return PC on a fetched call (DRIVEN by FetchAlign).
  *  - popValid              : pop on a fetched (predicted) return (DRIVEN by FetchAlign).
  *  - predValid (comb)      : count > 0 — a return can be predicted this cycle (OUTPUT).
  *  - predTarget (comb)     : ras[rasSp-1], the top-of-stack return PC (OUTPUT).
  *  - invalidateAll         : clear count/rasSp (driven from the I-cache invalidate).
  *
  * At most ONE push OR one pop per cycle (an instruction is a call XOR a return, and
  * the aligner emits at most one predicted-redirecting slot/cycle) -> single-ported. */
class RasPlugin extends FiberPlugin {

  val logic = during build new Area {
    val entries = Global.RAS_ENTRIES.get
    require((entries & (entries - 1)) == 0, "rasEntries must be a power of two")
    val spBits  = log2Up(entries)            // pointer width (wraps)
    val cntBits = log2Up(entries) + 1        // occupancy 0..entries (saturating)

    // ---- storage: a 16x32 register Vec (1-cycle combinational read; tiny) ----
    // RegInit so SpinalSim does not seed-randomize the contents (a head read of an
    // entry while count claims it valid would otherwise be flaky garbage).
    val ras   = Vec.fill(entries)(RegInit(U(0, 32 bits)))
    val rasSp = RegInit(U(0, spBits bits))   // speculative top-of-stack (next push slot)
    val count = RegInit(U(0, cntBits bits))  // saturating occupancy (0..entries)
    rasSp.simPublic(); count.simPublic()

    // ---- ports (directionless plain wires, idle-defaulted with concrete zeros) ----
    val pushValid = Bool();        pushValid.allowOverride;  pushValid := False
    val pushRetPc = UInt(32 bits); pushRetPc.allowOverride;  pushRetPc := U(0, 32 bits)
    val popValid  = Bool();        popValid.allowOverride;   popValid  := False
    val invalidateAll = Bool();    invalidateAll.allowOverride; invalidateAll := False

    // ---- combinational predict read: top-of-stack, valid iff non-empty ----
    val predValid  = (count =/= U(0, cntBits bits))
    val predTarget = ras(rasSp - U(1, spBits bits))
    predValid.simPublic(); predTarget.simPublic()

    val cntMax = U(entries, cntBits bits)

    // ---- push (call): ras[rasSp] := retPc ; rasSp++ ; count := min(count+1, entries).
    //      Overflow (push when full) is circular — the pointer wraps and overwrites the
    //      oldest; count saturates. A hint structure; EU-verify covers a wrong guess.
    when(pushValid) {
      ras(rasSp) := pushRetPc
      rasSp      := rasSp + U(1, spBits bits)
      when(count =/= cntMax) { count := count + U(1, cntBits bits) }
    }
    // ---- pop (predicted return): rasSp-- ; count := max(count-1, 0). FetchAlign only
    //      asserts popValid when predValid (count>0), so this never underflows the
    //      saturation; the guard is belt-and-suspenders. push XOR pop (call XOR return),
    //      so the two never collide on the same cycle.
    when(popValid) {
      rasSp := rasSp - U(1, spBits bits)
      when(count =/= U(0, cntBits bits)) { count := count - U(1, cntBits bits) }
    }

    // ---- invalidateAll: clear occupancy (and the pointer) on an I-cache flush ----
    when(invalidateAll) {
      count := U(0, cntBits bits)
      rasSp := U(0, spBits bits)
    }
  }
}
