package m68k040.debug

import m68k040.frontend.FetchAlignPlugin
import m68k040.rob.RobPlugin
import m68k040.services.{CommitTraceService, FrontendQuiesceService}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** Vivado VIO observation/actuation group (design spec `2026-08-18-vio-jtag-debug-design.md`,
  * V2-V6). Granted a deliberate, enumerated exception to axi-socket D23 ("the socket top
  * exports only socket ports") for the same reason group 7's ILA export already has one in
  * `macqd700-soc/rtl/soc/cpu_socket.vh:178-196`: a polled `dbg_axi` register read cannot be
  * performed at all when `dbg_axi` is the thing that is wedged, and cannot be performed during
  * reset under any circumstances (spec section 1).
  *
  * `enable` defaults FALSE (V4) -- with it false, this Area's PORT surface stays exactly what
  * axi-socket D23 froze `M68kFullCoreSynth` as (Task 1's own test pins "adds zero port surface"
  * as an automated check). Since Task 2, `logic.vioCpuSnapshot`/`.vioHeartbeat`/`.vioBuildId` are
  * declared unconditionally (Scala does not promote `val`s scoped inside an `if (enable) {...}`
  * block to members reachable from outside it, and Task 3/5 need a flat `logic.vioCpuSnapshot`),
  * so an `enable=false` build still carries three named, zero-constant-driven, zero-fanout wires
  * in its generated Verilog (confirmed harmless: no port, no `vio_`-prefixed name, and a
  * synthesiser trivially constant-folds them away) -- "elaborates to nothing" is therefore a
  * port-surface claim, not a literal zero-signal one. The socket top (once it exists, per V5)
  * instantiates this with `enable=true` and no gate (V3): the SoC decides whether to bind the
  * group to a real VIO IP, and an unbound group-8 output on a VIO-less build is simply an
  * unconnected output.
  *
  * Placed AFTER RobPlugin in any plugin list that includes it, since Task 2 consumes
  * `CommitTraceService`/`FrontendQuiesceService`/`host[RobPlugin]` -- none of which exist until
  * RobPlugin has built.
  *
  * `buildId` mirrors `DebugCtrlPlugin`'s own `buildId` constructor-param convention (NOT read
  * from `Global`) -- the caller (`FullCoreSynth.buildWith`) threads the same hex-parsed
  * `DBG_BUILD_ID` value into both plugins. */
class VioProbePlugin(val enable: Boolean = false, val buildId: BigInt = 0) extends FiberPlugin {

  val logic = during build new Area {
    // Task 2's V8/V9/V15 public interface, declared UNCONDITIONALLY: Scala does not
    // promote `val`s declared only inside an `if (enable) { ... }` block to members of
    // the enclosing anonymous `Area` class (they are locals of that block's own scope),
    // so `logic.vioCpuSnapshot`/`.vioHeartbeat`/`.vioBuildId` would not resolve for
    // Task 3/5 -- or this file's own tests -- if left nested inside the guard below.
    // With `enable=false` each is driven by a bare constant with no `host[...]`
    // reach-in and no register -- but, confirmed empirically (`grep -c` against the
    // generated `M68kFullCoreSynth.v`), the three named wires themselves still survive
    // Spinal's pruning pass even though nothing reads them (pruning removes anonymous
    // intermediate nodes, not user-named `val`s). Harmless either way: no port, no
    // `vio_`-prefixed name, and a synthesiser trivially constant-folds a zero-driven,
    // zero-fanout wire away -- Task 1's "adds zero port surface" contract (its own
    // automated test) is unaffected regardless.
    val vioCpuSnapshot = Bits(96 bits)
    val vioHeartbeat    = UInt(32 bits)
    val vioBuildId      = Bits(32 bits)
    vioCpuSnapshot := B(0, 96 bits)

    if (enable) {
      // Task 2: the probe_in side -- pure observation, no host-driven input, no
      // edge-triggering concerns. Task 3 adds probe_out (V16's mandatory-drain-with-
      // no-exception edge discipline) as a separate concern/review.
      val ct  = host[CommitTraceService]
      val rob = host[RobPlugin]

      // ── V11/V12: the last-commit PC latch ────────────────────────────────────────
      // Qualify on `traceFire(k)`, a concrete `False` idle default
      // (RobPlugin.scala's `traceFireVec(k) := False`), NEVER on `trace(k).fire`, which
      // sits inside a bundle defaulted `assignDontCare()` (RobPlugin.scala's
      // `traceVec(k).assignDontCare()`) and reads X between retires. Qualifying on the
      // wrong signal would latch garbage into synthesizable RTL between retires.
      //
      // Slot 1 wins over slot 0 on a dual retire (V12): the younger instruction is the
      // more recent PC. Two `when`s with slot-1 LAST -- SpinalHDL's last-assignment-wins
      // gives it priority with no explicit priority-encoder chain.
      val pcLatch      = Reg(UInt(32 bits)) init 0
      val pcLatchValid = RegInit(False)
      when(ct.traceFire(0)) { pcLatch := ct.trace(0).pc; pcLatchValid := True }
      when(ct.traceFire(1)) { pcLatch := ct.trace(1).pc; pcLatchValid := True }
      pcLatch.simPublic(); pcLatchValid.simPublic()

      // ── V13: saturating retire counter ───────────────────────────────────────────
      // Reuses DebugCtrlPlugin's Task-11 `cpuResetCount` saturating-counter PATTERN but
      // NOT its exact guard: that counter increments by exactly 1 and guards with
      // `=/= max`. This one increments by 0, 1 or 2 (dual retire) -- a `=/= max` guard
      // with n=2 can step OVER the maximum and wrap, exactly the failure the original's
      // own comment forbids. The guard here is `< max - n` instead, so a dual retire at
      // (max-1) saturates cleanly to max rather than wrapping to 0/1.
      val retireCount = Reg(UInt(32 bits)) init 0
      val n    = U(0, 2 bits) +^ ct.traceFire(0).asUInt +^ ct.traceFire(1).asUInt   // 0, 1 or 2
      val maxU = U(0xFFFFFFFFL, 32 bits)
      when(retireCount < (maxU - n.resize(32 bits))) {
        retireCount := retireCount + n.resize(32 bits)
      } otherwise {
        retireCount := maxU
      }
      retireCount.simPublic()

      // ── V15: reset-less heartbeat ─────────────────────────────────────────────────
      // `porCd` construction copied verbatim from `DebugCtrlPlugin.scala`'s own `porCd`:
      // `resetKind = BOOT` means "no reset wire; the init value is the boot value", so
      // this domain's flops keep running through a held core reset -- its entire job is
      // answering "is the core clock still running" DURING reset, which a counter in the
      // ordinary reset domain cannot do (it reads zero while reset is held,
      // indistinguishable from a dead clock).
      val coreCd = ClockDomain.current
      val porCd  = ClockDomain(clock  = coreCd.clock,
                               config = coreCd.config.copy(resetKind = BOOT))
      porCd.setSynchronousWith(coreCd)
      // porCd has no reset at all, so this process has no if/else gating and runs
      // unconditionally every cycle -- the DebugCtrlPlugin.scala precedent's own noted
      // elaboration subtlety, re-stated here so it is not rediscovered as a bug.
      val heartbeatArea = porCd on new Area {
        val cnt = Reg(UInt(32 bits)) init 0
        cnt := cnt + 1
      }
      vioHeartbeat := heartbeatArea.cnt

      // ── V8/V9: assemble the coherent 96-bit bundle ───────────────────────────────
      // One register-driven bundle so a single JTAG/VIO transaction captures every
      // field together and every fabric-side value is mutually consistent.
      //
      // `fetchStartedBit` reads `FetchAlignPlugin`'s `started` Reg via a direct
      // `host[FetchAlignPlugin].logic.started` read -- this codebase's established
      // convention for a sibling plugin reaching into another plugin's public `logic`
      // fields with no dedicated service trait (see `IplAckPlugin.scala`'s
      // `host[RobPlugin].logic.exc` / `.logic.commitObs(2)` reads). `started` has no
      // existing service accessor and adding a single-method trait for one boolean read
      // would be more machinery than the read itself.
      val commitPcValid      = pcLatchValid
      val haltedBit          = rob.logic.coreHalted
      val stoppedBit         = rob.logic.stopped
      val frontendQuiesceBit = host[FrontendQuiesceService].active
      val fetchStartedBit    = host[FetchAlignPlugin].logic.started

      // Sub-range assignment overrides only the touched bits of the `B(0, 96 bits)`
      // default declared above -- every untouched bit (the two reserved ranges) keeps
      // reading zero (V7/V9).
      vioCpuSnapshot(31 downto 0)  := retireCount.asBits
      vioCpuSnapshot(63 downto 32) := pcLatch.asBits
      vioCpuSnapshot(64)           := commitPcValid
      vioCpuSnapshot(65)           := haltedBit
      // [69:66] halt_reason -- RESERVED, reads zero until axi-socket D28 lands (V21).
      // Building a second, VIO-only halt-reason encoding here would create exactly the
      // divergence D28 exists to prevent -- do not wire anything into this range.
      vioCpuSnapshot(70)           := stoppedBit
      vioCpuSnapshot(71)           := frontendQuiesceBit
      vioCpuSnapshot(72)           := fetchStartedBit
      // [95:73] reserved, reads zero (V9).

      vioBuildId := B(buildId, 32 bits)   // `buildId` is this plugin's own constructor
                                           // param (added alongside `enable`), NOT a
                                           // Global key

      // `.simPublic()` scoped to the `enable=true` branch only (it exists purely so
      // whitebox tests can peek these signals; the `enable=false` build needs no such
      // access, so there is no reason to call it there even though -- per the note
      // above the top-level `val` declarations -- it turns out to make no difference to
      // what survives pruning either way).
      vioCpuSnapshot.simPublic()
      vioHeartbeat.simPublic()
      vioBuildId.simPublic()
    } else {
      // `vioHeartbeat`/`vioBuildId` are FULL-WIDTH unconditional drives (unlike
      // `vioCpuSnapshot`, which only ever receives partial/sub-range assignments and so
      // never "completely overlaps" its own idle default) -- Spinal's
      // `PhaseCheck_noLatchNoOverride` flags two unconditional full-width drives of the
      // same signal as an assignment-overlap error even though only one Scala branch
      // ever elaborates, so the idle value is threaded through this `else` instead of a
      // second top-level default assignment.
      vioHeartbeat := U(0, 32 bits)
      vioBuildId   := B(0, 32 bits)
    }
  }
}
